package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的自定义分布式锁实现
 *
 * ===================== 面试考点：分布式锁设计 =====================
 * 【为什么需要分布式锁？】
 *   单机锁（synchronized/ReentrantLock）只在当前JVM有效。
 *   集群/分布式部署时，多台机器上的线程不共享锁，需要借助外部存储（如Redis）协调。
 *
 * 【Redis实现分布式锁的核心命令】
 *   SET key value NX PX milliseconds
 *   NX（Not eXists）：只有key不存在时才SET → 即加锁
 *   PX：设置过期时间 → 防止持锁线程崩溃后锁永不释放（死锁保护）
 *
 * 【本实现的已知问题（面试必答）】
 *   ① 不可重入：同一线程重复加锁会死锁（SETNX在key存在时返回false）
 *   ② 无自动续期：若业务执行时间超过lockTTL，锁自动过期，其他线程可获取锁，产生并发问题
 *   ③ 不支持等待重试（只有tryLock，无waitLock）
 *   ④ 非公平锁（无排队机制）
 *   以上问题 Redisson 全部解决，生产环境优先使用 Redisson。
 * ================================================================
 */
public class SimpleRedisLock implements ILock {

    /** 业务名称（如 "order"），用于构造锁的key */
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** Redis锁key的前缀，最终锁key = "lock:{name}" */
    private static final String KEY_PREFIX = "lock:";

    /**
     * 锁值的前缀：UUID + 线程ID
     *
     * 【面试考点】为什么锁值不直接用线程ID？
     *   单机内线程ID在JVM中唯一，但多台机器上可能出现相同线程ID（如都是线程1）。
     *   UUID在应用启动时生成一次（static，每个JVM实例不同）+ 线程ID，全局唯一。
     *   加锁时存入此值，解锁时验证此值，确保只有持锁线程能释放自己的锁。
     *
     * 【面试考点】为什么是 static final？
     *   UUID在类加载时生成一次，同一JVM实例所有锁共享同一UUID前缀，
     *   线程ID在同一JVM内唯一，两者拼接确保多JVM多线程下锁值全局唯一。
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    /**
     * 解锁Lua脚本（加载一次，静态复用）
     *
     * 【面试考点】为什么解锁要用Lua脚本？
     *   解锁需要两步：① GET key（获取锁值）② 比较后 DEL key（删除锁）
     *   这两步如果分开执行（非原子），存在以下风险：
     *     线程A执行GET，得到自己的threadId，此时锁TTL恰好过期被Redis删除
     *     线程B执行SETNX成功，获取了新锁
     *     线程A误以为自己还持锁，执行DEL，把线程B的锁删掉了！→ 其他线程可以再次加锁
     *   Lua脚本在Redis中原子执行，GET和DEL之间不会被打断，彻底避免上述问题。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 尝试加锁
     *
     * 底层命令：SET lock:{name} {UUID}-{threadId} NX EX {timeoutSec}
     *
     * @param timeoutSec 锁超时时间（秒），防死锁。建议设置为业务最大执行时间的 2~3 倍。
     * @return true=加锁成功，false=锁已被其他线程持有（加锁失败）
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        // 锁值 = UUID前缀 + 当前线程ID（全局唯一，用于释放锁时验证归属）
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // setIfAbsent = SET key value NX PX：只有key不存在时才设置，附带过期时间
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // Boolean对象拆箱时可能NPE，用 Boolean.TRUE.equals 安全比较
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁（使用Lua脚本保证原子性）
     *
     * Lua脚本逻辑（见 unlock.lua）：
     *   if GET(KEYS[1]) == ARGV[1] then DEL(KEYS[1]) end
     *   即：只有当前锁的值等于我的threadId时，才执行删除
     *
     * 传参：
     *   KEYS[1] = "lock:{name}"（锁的key）
     *   ARGV[1] = "{UUID}-{threadId}"（当前线程的锁值，用于验证锁归属）
     */
    @Override
    public void unlock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),   // KEYS[1]
                ID_PREFIX + Thread.currentThread().getId());     // ARGV[1]
    }

    /*
     * 【面试考点】早期的错误解锁实现（非原子，有Bug，仅供对比理解）：
     *
     * @Override
     * public void unlock() {
     *     // 问题所在：get 和 delete 是两个独立操作，中间可能被打断！
     *     String threadId = ID_PREFIX + Thread.currentThread().getId();
     *     String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
     *     // 此处如果发生线程切换、GC STW、或锁TTL过期：
     *     //   ① 锁TTL过期 → 其他线程B加了新锁
     *     //   ② 当前线程A继续执行 threadId.equals(id) 判断为true（当时拿到的是自己的值）
     *     //   ③ delete 把线程B的锁删掉了！→ 锁被误删，并发安全性被破坏
     *     if(threadId.equals(id)) {
     *         stringRedisTemplate.delete(KEY_PREFIX + name);
     *     }
     * }
     */
}
