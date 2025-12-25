package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    // 注入 Spring 管理的 StringRedisTemplate，用于操作 Redis 中的字符串和列表
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 构造缓存使用的 key（店铺类型列表是一个固定 key）
        String key = CACHE_SHOP_TYPE_KEY;
        // 从 Redis 的 List 结构中一次性读取全部元素（0 到 -1 表示整个列表）
        List<String> typeJsonList = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (CollUtil.isNotEmpty(typeJsonList)) {
            // 如果缓存中有数据：将 JSON 字符串列表转换为 ShopType 对象列表
            List<ShopType> typeList = typeJsonList.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(typeList);
        }
        // 缓存未命中：从数据库按 sort 字段升序查询所有店铺类型
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (CollUtil.isEmpty(typeList)) {
            return Result.fail("店铺类型不存在");
        }
        // 将查询到的 ShopType 列表转换为 JSON 字符串列表，准备写入 Redis 的 List 结构
        List<String> jsonList = typeList.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        // 依次将 JSON 字符串追加到 Redis 列表中，保持与数据库中 sort 排序一致
        stringRedisTemplate.opsForList().rightPushAll(key, jsonList);
        return Result.ok(typeList);
    }
}
