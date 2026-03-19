package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于 Redis 的分布式全局唯一ID生成器
 *
 * ===================== 面试考点：ID生成方案 =====================
 * 【为什么不用数据库自增ID？】
 *   ① ID规律性强，暴露业务信息（如通过订单ID推算日订单量）
 *   ② 分库分表后ID会冲突（每张表各自自增，会重复）
 *   ③ 数据库自增在高并发下存在性能瓶颈
 *
 * 【为什么不用UUID？】
 *   UUID无序（随机字符串），作为数据库主键时B+树频繁页分裂，性能差；
 *   且长度36字符，存储空间大。
 *
 * 【本方案：时间戳 + Redis自增序列号】
 *   有序（时间戳递增），高性能（Redis INCR），全局唯一。
 *   与雪花算法类比：雪花算法用机器ID保证分布式唯一，本方案用Redis自增替代机器ID。
 *
 * 【ID结构（64位 long）】
 *   | 符号位(1bit=0) | 时间戳(31bit) | 序列号(32bit) |
 *   - 符号位0：保证ID始终为正数
 *   - 时间戳：以2022-01-01 00:00:00为起点的秒级差值，支持 2^31秒 ≈ 68年
 *   - 序列号：Redis按天自增（key = "icr:{prefix}:{yyyy:MM:dd}"），每天支持 2^32 ≈ 42亿个
 * ================================================================
 */
@Component
public class RedisIdWorker {

    /**
     * 起始时间戳：2022-01-01 00:00:00 UTC 对应的秒数
     *
     * 【面试考点】为什么不用0（Unix时间戳起点1970年）？
     *   时间戳占31bit，最大值 2^31 ≈ 2147483648 秒 ≈ 68年。
     *   若从1970年起步，到2038年就溢出了（著名的Y2038问题）。
     *   以2022年为起点，可用到2022+68=2090年，满足长期使用需求。
     *
     * 计算方式：LocalDateTime.of(2022,1,1,0,0,0).toEpochSecond(ZoneOffset.UTC) = 1640995200
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 序列号占用的bit位数
     *
     * 【面试考点】为什么是32位？
     *   32位序列号支持每天 2^32 ≈ 42亿个ID，远超实际业务需求。
     *   时间戳左移32位后，低32位全部留给序列号（位或操作拼接）。
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成下一个全局唯一ID
     *
     * 【面试考点】ID生成步骤：
     *   ① 计算时间戳：当前秒数 - BEGIN_TIMESTAMP（得到相对秒数，fit 31bit）
     *   ② 获取序列号：Redis INCR "icr:{keyPrefix}:{yyyy:MM:dd}"（按天自增，线程安全）
     *      - INCR是原子操作，多线程并发下不会重复
     *      - 按天分key，防止单key无限增大；key自然带有日期，方便按日统计
     *   ③ 拼接：timestamp << 32 | count
     *      - 左移32位：时间戳占高31位（符号位0在最高位）
     *      - 位或：低32位填入序列号
     *
     * 【面试考点】举例：
     *   timestamp = 100（第100秒）= 0b...01100100
     *   左移32位后 = 0b01100100_00000000_00000000_00000000_00000000（时间戳在高位）
     *   count = 5（第5个订单）= 0b...00000101
     *   位或结果 = 0b01100100_00000000_00000000_00000000_00000101
     *   最终ID约为 4294967301（一个正long整数）
     *
     * @param keyPrefix 业务前缀（如 "order"），不同业务各自计数，key为 "icr:order:2024:01:01"
     * @return 全局唯一的64位正整数ID
     */
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳：当前UTC秒数 - 起始秒数（得到相对偏移量，确保值在31bit范围内）
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号（利用Redis原子自增）
        // 2.1 获取当前日期字符串（精确到天），作为key的一部分
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 Redis INCR：原子自增，并发安全；key按天分散，防止单key过大
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3. 位运算拼接：时间戳左移32位（腾出低32位空间）| 序列号（填入低32位）
        // 结果是一个64位long，高31位是时间戳，低32位是序列号，最高位符号位为0（正数）
        return timestamp << COUNT_BITS | count;
    }
}
