-- ===================== seckill.lua - 秒杀核心Lua脚本 =====================
-- 【面试考点】为什么用Lua脚本？
--   Redis单线程执行Lua脚本，整个脚本是一个不可分割的原子操作。
--   5个操作（校验库存、校验一人一单、扣库存、记录用户、发消息）在同一个原子单元中执行，
--   彻底避免 TOCTOU（Time-Of-Check-Time-Of-Use）并发漏洞：
--   ① 不会出现"A和B同时判断库存>0，都通过，但只有1个库存"的超卖问题
--   ② 不会出现"A和B同时判断未下单，都通过，造成重复下单"的问题
-- ======================================================================

-- 1. 接收Java传入的参数（通过 ARGV 数组）
-- 【注意】Lua数组下标从1开始（不像Java从0开始）
local voucherId = ARGV[1]   -- 优惠券ID（由Java层传入）
local userId = ARGV[2]      -- 用户ID（防止重复下单的用户标识）
local orderId = ARGV[3]     -- 预先生成的订单ID（由 RedisIdWorker 生成）

-- 2. 构造Redis中的key（Lua字符串拼接用 ".."）
local stockKey = 'seckill:stock:' .. voucherId   -- 存储库存数量的String key，如 seckill:stock:1
local orderKey = 'seckill:order:' .. voucherId   -- 存储已下单用户的Set key，如 seckill:order:1

-- 3. 核心校验和扣减逻辑（原子执行）

-- 3.1 校验库存是否充足
-- tonumber()：将Redis返回的字符串转为数字进行比较
-- 【面试考点】Redis中的数字以字符串形式存储，直接比较会出错，必须用tonumber转换
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足，返回1（Java层会据此返回"库存不足"错误）
    return 1
end

-- 3.2 校验一人一单：检查当前用户是否已在已购买Set中
-- SISMEMBER：O(1)时间复杂度，返回1表示已存在（已下单），返回0表示不存在（未下单）
-- 【面试考点】用Set而不是查DB：Redis内存操作比DB快100倍以上，在秒杀热点路径上必须用内存
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 用户已下单，返回2（Java层会据此返回"不能重复下单"错误）
    return 2
end

-- 校验通过后，执行扣减和记录（以下三步在同一原子操作内）

-- 3.3 扣减库存：INCRBY key -1（等效于DECR）
-- 【面试考点】不用SET(stock-1)而用INCRBY：INCRBY是原子操作，SET需要先GET再计算，非原子
redis.call('incrby', stockKey, -1)

-- 3.4 将用户ID加入已下单Set，防止该用户再次下单
-- SADD：Set的添加操作，自动去重
redis.call('sadd', orderKey, userId)

-- 3.5 将订单信息发送到Redis Stream消息队列，供后台线程异步处理DB写入
-- XADD stream.orders * k1 v1 k2 v2 ...
--   stream.orders：Stream的key（消息队列名称）
--   *：自动生成消息ID（时间戳-序列号格式，如 1703123456789-0）
--   后续 k1 v1 ...：消息体的键值对
-- 【面试考点】为什么在Lua中发消息？
--   保证"扣库存"和"发消息"是原子的，不会出现扣了库存但消息没发出的情况
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

-- 返回0表示成功，Java层据此返回订单ID给用户
return 0
