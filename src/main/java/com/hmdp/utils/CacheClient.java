package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * 缓存客户端工具类，封装了三种核心缓存策略：
 *   1. queryWithPassThrough  → 解决「缓存穿透」
 *   2. queryWithMutex        → 解决「缓存击穿」（互斥锁方案，强一致）
 *   3. queryWithLogicalExpire→ 解决「缓存击穿」（逻辑过期方案，高可用）
 *
 * ===================== 面试考点 =====================
 * 【缓存穿透】请求的数据在缓存和DB中都不存在，绕过缓存直接打DB。
 *   解决：将不存在的key也缓存为空值（""），TTL较短（2min），拦截相同请求。
 *
 * 【缓存击穿】热点Key过期瞬间，大量并发请求同时打DB重建缓存。
 *   解决方案A（互斥锁）：只让一个线程重建缓存，其他线程等待，保证强一致。
 *   解决方案B（逻辑过期）：永不过期，异步重建，牺牲短暂一致性换取高可用。
 * ===================================================
 */
@Slf4j
@Component
public class CacheClient {

    // 操作 Redis 的模板，专门处理字符串相关的数据结构
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 【面试考点】缓存重建专用线程池
     * 逻辑过期方案中，不阻塞当前请求线程，而是提交到独立线程池异步重建缓存。
     * 线程池大小10：缓存重建任务耗时短（查DB+写Redis），10个线程足够处理并发。
     * 使用静态常量确保全局唯一，避免频繁创建线程池开销。
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 普通写缓存：直接将对象序列化为 JSON 写入 Redis，并设置物理 TTL
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 写入带逻辑过期时间的缓存
     *
     * 【面试考点】逻辑过期 vs 物理TTL：
     *   物理TTL：Redis自动删除key，过期后key不存在
     *   逻辑过期：key永不消失（不设TTL），在value中额外存一个过期时间字段（RedisData.expireTime）
     *   读取时由业务代码判断是否"逻辑过期"，若过期则异步重建缓存，同时返回旧数据
     *   好处：不存在key突然消失导致缓存击穿的问题
     *   坏处：需要提前做缓存预热（key需要预先写入Redis）
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 用 RedisData 包装：将实际业务数据 + 逻辑过期时间一起存储
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 注意：这里不设置Redis的TTL，key永久存在
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 【核心方案一】解决缓存穿透：缓存空值
     *
     * 流程：
     *   ① 查Redis：命中有效值 → 直接返回
     *   ② 查Redis：命中空值("") → 返回null（拦截！不查DB）
     *   ③ 查Redis：未命中 → 查DB：DB无数据 → 缓存空值(TTL=2min) → 返回null
     *   ④ 查Redis：未命中 → 查DB：DB有数据 → 写缓存(TTL=30min) → 返回数据
     *
     * 【面试考点】为什么要缓存空值？
     *   防止恶意请求用不存在的ID反复攻击，导致每次都打DB。
     *   空值TTL设2分钟：太短起不到防护作用；太长会导致数据新增后短时间内查不到。
     *
     * 【面试考点】与布隆过滤器对比：
     *   缓存空值：简单，但占内存，有短暂不一致窗口
     *   布隆过滤器：内存极省，但有误判率，维护成本高（数据新增需同步更新）
     *
     * @param keyPrefix  缓存键前缀（如 "cache:shop:"）
     * @param id         业务ID（如商铺ID）
     * @param type       返回值类型的Class对象，用于JSON反序列化
     * @param dbFallback 数据库查询的函数式接口，传入ID返回实体（lambda表达式）
     * @param time       缓存TTL数值
     * @param unit       缓存TTL单位
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1. 从Redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 命中有效数据（非null、非空串）→ 直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 3. 命中空值（json != null 但 isBlank，即 json == ""）→ 说明是之前缓存的"不存在"标记，直接返回null
        if (json != null) {
            // 此处就是缓存穿透防护的关键：命中空值，不再查DB
            return null;
        }

        // 4. 完全未命中（json == null）→ 查询数据库
        R r = dbFallback.apply(id);
        // 5. DB也没有 → 缓存空值（防止缓存穿透），TTL短一些（CACHE_NULL_TTL = 2min）
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6. DB有数据 → 写入缓存，返回结果
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 【核心方案二】解决缓存击穿：逻辑过期 + 异步缓存重建
     *
     * 流程：
     *   ① 查Redis：key不存在 → 返回null（使用前需预热，key必须存在）
     *   ② 查Redis：存在 → 反序列化出实际数据 + 逻辑过期时间
     *   ③ 逻辑未过期 → 直接返回数据（最常见路径）
     *   ④ 逻辑已过期 → 尝试获取互斥锁：
     *       获取锁失败 → 直接返回旧数据（保证高可用，不阻塞）
     *       获取锁成功 → 提交异步任务到线程池，在新线程中查DB重建缓存，当前线程返回旧数据
     *
     * 【面试考点】为什么获取锁失败也返回旧数据？
     *   因为已有其他线程在重建缓存了，没必要等待；保证请求不阻塞，吞吐量高。
     *
     * 【面试考点】为什么需要互斥锁？
     *   多个请求同时发现逻辑过期，如果都去查DB重建缓存，会造成DB压力（缓存击穿）。
     *   只有第一个拿到锁的线程去重建，其他线程继续用旧数据。
     *
     * 【面试考点】此方案vs互斥锁方案：
     *   逻辑过期：不阻塞，高可用，但短暂返回旧数据（最终一致）
     *   互斥锁：强一致，但其他线程阻塞等待（吞吐量低）
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 查Redis
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 未命中 → 返回null（逻辑过期方案要求提前预热，正常情况下key一定存在）
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 3. 命中 → 反序列化：将JSON解析为RedisData，再从data字段取出实际业务对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4. 判断逻辑过期时间
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 4.1 逻辑未过期 → 直接返回（最高频路径，无锁，性能最好）
            return r;
        }
        // 4.2 逻辑已过期 → 需要异步重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock){
            // 获取锁成功 → 提交异步任务到独立线程池（不阻塞当前请求线程）
            // 注意：这里应该做 DoubleCheck（再次检查是否真的过期），避免多次重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库（dbFallback 即 this::getById 等lambda）
                    R newR = dbFallback.apply(id);
                    // 重建缓存（写入新数据+新的逻辑过期时间）
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 无论成功失败，都要释放锁
                    unlock(lockKey);
                }
            });
        }
        // 无论是否获取锁，都返回旧数据（保证可用性）
        return r;
    }

    /**
     * 【核心方案三】解决缓存击穿：互斥锁（强一致方案）
     *
     * 流程：
     *   ① 查Redis：命中 → 返回
     *   ② 查Redis：命中空值 → 返回null
     *   ③ 查Redis：未命中 → 尝试获取互斥锁
     *       获取锁失败 → sleep 50ms → 递归重试（最终会等到缓存重建完成）
     *       获取锁成功 → 查DB → 写缓存 → 释放锁
     *
     * 【面试考点】获取锁成功后是否需要 DoubleCheck？
     *   应该需要！多个线程同时发现缓存未命中，第一个线程重建缓存后，
     *   后续线程拿到锁时缓存已经存在了，应该再查一次Redis，避免重复查DB。
     *   本项目简化处理，实际生产建议加DoubleCheck。
     *
     * 【面试考点】锁的TTL为什么是10秒？
     *   需覆盖"查DB + 写缓存"的最大预期时间，防止业务未完成锁就过期（锁失效）。
     *   太长则进程崩溃后其他线程需等待很久。生产建议用Redisson看门狗自动续期。
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 查Redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 命中有效数据 → 直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, type);
        }
        // 3. 命中空值 → 返回null（缓存穿透防护）
        if (shopJson != null) {
            return null;
        }

        // 4. 未命中 → 加锁重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.1 获取锁失败 → sleep后递归重试（等待其他线程重建完成）
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.2 获取锁成功 → 查DB（此处生产建议先做DoubleCheck，再查Redis一次）
            r = dbFallback.apply(id);
            // 4.3 DB无数据 → 缓存空值防穿透
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 4.4 DB有数据 → 写入缓存
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 5. 无论成功失败，必须释放锁（try-finally保证）
            unlock(lockKey);
        }
        return r;
    }

    /**
     * 尝试获取互斥锁
     *
     * 【面试考点】实现原理：Redis SETNX（SET if Not eXists）
     *   setIfAbsent = SET key "1" NX PX 10000
     *   NX保证原子性：只有key不存在时才设置成功（即加锁成功）
     *   TTL=10s：防止持锁线程崩溃后锁永远不释放（死锁保护）
     *   值为"1"：此处锁不区分持锁线程（CacheClient的互斥锁不需要判断锁归属）
     *   与SimpleRedisLock不同：SimpleRedisLock的值是UUID+ThreadId，释放时需验证
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 不直接返回flag（可能NPE），用BooleanUtil安全拆箱
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁：直接删除key
     *
     * 【注意】此处直接删除，没有验证锁的归属（因为CacheClient的锁value="1"，无归属信息）。
     * 业务上由try-finally保证只有加锁成功的线程才会执行unlock，所以是安全的。
     * 对比 SimpleRedisLock 的unlock：用Lua脚本原子验证+删除，更严格。
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
