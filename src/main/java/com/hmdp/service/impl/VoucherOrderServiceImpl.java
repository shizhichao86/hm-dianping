package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 优惠券秒杀服务实现类
 *
 * ===================== 面试考点：整体秒杀架构 =====================
 * 核心思路：将秒杀的「资格校验 + Redis扣减」与「DB写入」分离，通过异步处理大幅提升并发性能。
 *
 * 完整链路：
 *   ① 用户请求 seckillVoucher()
 *   ② 执行 seckill.lua（Redis中原子完成 5 个操作，耗时极短）
 *      - 校验秒杀时间（此版本简化，时间校验前置）
 *      - 校验库存（GET seckill:stock:{voucherId}）
 *      - 校验一人一单（SISMEMBER seckill:order:{voucherId} userId）
 *      - 扣减Redis库存（INCRBY stockKey -1）
 *      - 记录已下单用户（SADD orderKey userId）
 *      - 发送消息到Stream队列（XADD stream.orders）
 *   ③ Lua返回0 → 立即返回订单ID给用户（用户感知低延迟）
 *   ④ 后台线程 VoucherOrderHandler 从 stream.orders 消费消息
 *   ⑤ Redisson分布式锁防止相同userId并发处理（兜底）
 *   ⑥ DB查重（count>0 兜底）+ DB扣库存（stock>0乐观锁）+ DB创建订单
 *   ⑦ XACK 消息确认，从Pending List移除
 * ================================================================
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * Redisson客户端：用于获取分布式锁（比自定义SimpleRedisLock功能更强大）
     *
     * 【面试考点】为什么用Redisson而不用自定义SimpleRedisLock？
     *   SimpleRedisLock的问题：①不可重入 ②无自动续期（锁过期业务未完成会产生并发问题）③非公平
     *   Redisson提供：①可重入锁 ②看门狗自动续期（每10s刷新TTL至30s）③公平锁/读写锁等
     */
    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 秒杀Lua脚本：原子执行库存校验、一人一单校验、扣库存、记录用户、发MQ消息
     *
     * 【面试考点】为什么用静态代码块加载？
     *   DefaultRedisScript 加载Lua文件涉及IO操作，静态块在类加载时执行一次，
     *   避免每次秒杀请求都重新加载文件（性能优化）。
     *
     * 【面试考点】为什么用Lua脚本？
     *   Redis单线程执行Lua脚本，5个操作作为一个不可分割的原子单元，
     *   避免TOCTOU（Time-Of-Check-Time-Of-Use）并发漏洞，防止超卖和重复下单。
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 后台订单处理线程池：单线程执行，保证消息顺序消费
     *
     * 【面试考点】为什么用单线程？
     *   Redis Stream的消费者组模式下，同一消费者串行处理，保证订单处理的顺序性。
     *   秒杀场景DB写入的瓶颈不在线程数，而在DB本身，单线程简化设计、避免并发问题。
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 【面试考点】@PostConstruct 的作用：
     *   在Bean初始化完成（依赖注入完成）后立即执行，启动后台消费线程。
     *   为什么不在构造函数中启动？构造函数执行时@Resource注入尚未完成，线程中用到的
     *   stringRedisTemplate 等字段还是null，会NullPointerException。
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * Redis Stream 消息消费者（后台持续运行的任务）
     *
     * ===================== 面试考点：Redis Stream 消费者组 =====================
     * 消费者组（Consumer Group）工作原理：
     *   1. 生产者（Lua脚本）：XADD stream.orders * k1 v1 ...   → 写入消息
     *   2. 消费者读取：XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
     *      - GROUP g1：消费者组名
     *      - c1：消费者名
     *      - BLOCK 2000：阻塞等待2秒，无消息则返回null
     *      - >：读取未被任何消费者消费过的新消息
     *   3. 消息被读取后进入消费者的 Pending List（待确认列表）
     *   4. 处理成功后：XACK stream.orders g1 {id} → 消息从Pending List移除
     *   5. 若处理失败（异常）：消息留在Pending List，通过 handlePendingList() 重试
     * =========================================================================
     */
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 从Stream消费者组读取消息，阻塞等待最多2秒
                    //    ReadOffset.lastConsumed() 对应 ">" → 读取新消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2. 无消息（超时返回null） → 继续循环等待
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    // 3. 解析消息体（Map结构：userId, voucherId, id）
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 4. 处理订单（DB写入）
                    createVoucherOrder(voucherOrder);
                    // 5. 确认消息（ACK），消息从Pending List中移除
                    //    【面试考点】ACK后消息才算真正消费完，未ACK的消息可以被重新处理
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 出现异常（处理失败）→ 消息留在Pending List → 调用handlePendingList重试
                    handlePendingList();
                }
            }
        }

        /**
         * 处理 Pending List 中未确认的消息（消费失败重试机制）
         *
         * 【面试考点】Pending List 重试机制：
         *   正常消费使用 ">" 读取新消息；
         *   异常恢复使用 ReadOffset.from("0") 读取Pending List（从消息ID "0" 开始，即最早未ACK的消息）。
         *   循环处理直到Pending List为空（list为null或空），确保没有遗漏的消息。
         */
        private void handlePendingList() {
            while (true) {
                try {
                    // 从Pending List读取消息，ReadOffset.from("0") = 读取Pending List中最早未ACK的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // Pending List为空 → 退出重试循环，回到正常消费
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    createVoucherOrder(voucherOrder);
                    // 重试成功后ACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 重试也失败，短暂等待后继续重试（避免CPU空转）
                    // 生产环境可加死信队列（DLQ）或告警机制
                }
            }
        }
    }

    /*
     * 已废弃方案：JVM内部阻塞队列（ArrayBlockingQueue）
     *
     * 【面试考点】为什么从阻塞队列升级为Redis Stream？
     *   阻塞队列的问题：
     *   ①内存限制：队列存在JVM内存中，服务重启消息丢失，无持久化
     *   ②无ACK机制：消息从队列取出即删除，处理失败消息会丢失
     *   ③无法共享：多个实例部署时，消息只在一个JVM中，无法负载均衡
     *   Redis Stream解决了以上问题：持久化+ACK机制+消费者组支持多实例
     */
    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);*/

    /**
     * 创建订单的核心业务逻辑（异步执行，由后台线程调用）
     *
     * 【面试考点】为什么异步后还需要Redisson锁？
     *   Lua脚本已在Redis层做了一人一单校验（SISMEMBER），但Redis与DB之间存在异步Gap。
     *   极端情况（如Redis数据丢失后重启、用户伪造请求绕过Lua）下，可能出现重复消息。
     *   Redisson锁 + DB查重是双重兜底，保证DB层面的一人一单。
     *
     * 【面试考点】为什么不直接用synchronized？
     *   synchronized是JVM级别的锁，多实例部署（集群）时无法跨JVM同步。
     *   Redisson是Redis分布式锁，跨JVM跨机器有效。
     *
     * @param voucherOrder 从Stream消息解析出的订单对象（含userId、voucherId、orderId）
     */
    private void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 获取 Redisson 分布式锁，锁粒度为"单个用户"，不同用户可并行处理
        // 锁key = "lock:order:{userId}"，保证同一用户的订单串行处理
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // tryLock()无参：不等待，立即返回；内部会启动看门狗自动续期
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            // 获取锁失败 → 说明该用户已有一个订单正在处理 → 直接丢弃（Lua已防重，此处极小概率触发）
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 【第三层兜底】DB查重：防止极端情况下重复消息导致重复下单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                log.error("不允许重复下单！");
                return;
            }

            // 【防超卖兜底】DB乐观锁扣减库存
            // WHERE voucher_id = ? AND stock > 0  → CAS操作，即使并发也不会扣减到负数
            // 【面试考点】为什么用 stock > 0 而不是版本号乐观锁？
            //   版本号：每次CAS只有一个线程成功，并发高时失败率极高
            //   stock > 0：只要有库存均可成功，成功率远高于版本号方案
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                log.error("库存不足！");
                return;
            }

            // 持久化订单到数据库
            save(voucherOrder);
        } finally {
            // 必须在finally中释放锁，确保异常情况下也能释放
            redisLock.unlock();
        }
    }

    /**
     * 秒杀优惠券入口方法
     *
     * 【面试考点】秒杀流程设计思路：
     *   传统做法：校验 → 查DB库存 → 扣DB库存 → 创建订单，所有操作串行在DB事务中，高并发下DB成为瓶颈。
     *   本项目做法：
     *     1. 将「校验+扣减」前移到 Redis（Lua脚本，毫秒级完成）
     *     2. DB写入异步化（消息队列，后台处理）
     *     3. 快速返回订单ID（用户感知极低延迟）
     *
     * @param voucherId 优惠券ID
     * @return 成功返回订单ID；失败返回"库存不足"或"不能重复下单"
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 预先生成订单ID（全局唯一ID，时间戳+Redis序列号）
        long orderId = redisIdWorker.nextId("order");

        // 执行Lua脚本：原子完成「校验库存 + 校验一人一单 + 扣Redis库存 + 记录用户 + 发Stream消息」
        // 参数：voucherId（ARGV[1]）、userId（ARGV[2]）、orderId（ARGV[3]）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),   // Lua中无KEYS参数（key在脚本内部拼接）
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        // Lua返回值含义：0=成功下单，1=库存不足，2=重复下单
        int r = result.intValue();
        if (r != 0) {
            // 非0：没有下单资格，直接返回（未写DB，对DB零压力）
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // Lua返回0：下单资格校验通过，消息已写入stream.orders，等待后台线程异步处理DB
        // 直接返回订单ID，用户可以凭此ID查询订单状态
        return Result.ok(orderId);
    }

    /*
     * 已废弃的同步秒杀方案（版本一：纯DB操作，无Redis优化）
     * 问题：所有操作在DB事务中串行执行，高并发下DB连接数耗尽，响应时间极长
     *
     * 已废弃的同步秒杀方案（版本二：Lua+阻塞队列）
     * 相比Redis Stream缺少持久化和ACK确认机制，服务重启会丢失队列中的订单
     */

    /*    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2.为0 ，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.6.放入阻塞队列
        orderTasks.add(voucherOrder);

        // 3.返回订单id
        return Result.ok(orderId);
    }*/


/*    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }

        return createVoucherOrder(voucherId);
    }

    @Resource
    private RedissonClient redissonClient;

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();

        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if(!isLock){
            // 获取锁失败，直接返回失败或者重试
            return Result.fail("不允许重复下单！");
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                return Result.fail("用户已经购买过一次！");
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                return Result.fail("库存不足！");
            }

            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2.用户id
            voucherOrder.setUserId(userId);
            // 7.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            // 7.返回订单id
            return Result.ok(orderId);
        } finally {
            // 释放锁
            redisLock.unlock();
        }

    }*/


//    @Resource
//    private StringRedisTemplate stringRedisTemplate;

    /*
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();

        // 创建锁对象
        SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock(1200);
        // 判断
        if(!isLock){
            // 获取锁失败，直接返回失败或者重试
            return Result.fail("不允许重复下单！");
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                return Result.fail("用户已经购买过一次！");
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                return Result.fail("库存不足！");
            }

            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2.用户id
            voucherOrder.setUserId(userId);
            // 7.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            // 7.返回订单id
            return Result.ok(orderId);
        } finally {
            // 释放锁
            redisLock.unlock();
        }

    }
     */

    /*
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();
        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足！");
        }

        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 7.返回订单id
        return Result.ok(orderId);
    }

    */
}
