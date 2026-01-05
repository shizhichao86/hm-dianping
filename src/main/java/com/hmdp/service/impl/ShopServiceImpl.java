package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 使用自定义的CacheClient封装缓存逻辑：
        // 当前启用的是“缓存穿透”方案：先查Redis，未命中再查数据库，最后写回缓存并设置TTL
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.先更新数据库中的店铺信息
        updateById(shop);
        // 2.再删除对应缓存，保证下次查询时能从数据库读取最新数据并重新写入缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 根据是否传入经纬度决定查询方式：
        // 1）未传经纬度：直接按类型走数据库分页；
        // 2）传入经纬度：基于Redis GEO查询附近商铺，再结合数据库补全信息
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        // key中包含类型id，保证查询的是同一类型下的店铺
        String key = SHOP_GEO_KEY + typeId;
        // 适配Redis 5.0：使用 GEORADIUS 对应的 radius API，而不是 GEOSEARCH
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo().radius(
                        key,
                        new Circle(new Point(x, y), new Distance(5000)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(end)
                );
        // 如果将来升级到 Redis 6.2+，可以使用 GEOSEARCH（search API），示例代码如下：
        // GeoResults<RedisGeoCommands.GeoLocation<String>> results =
        //         stringRedisTemplate.opsForGeo().search(
        //                 key,
        //                 org.springframework.data.redis.domain.geo.GeoReference.fromCoordinate(x, y),
        //                 new Distance(5000),
        //                 RedisGeoCommands.GeoSearchCommandArgs
        //                         .newGeoSearchArgs().includeDistance().limit(end)
        //         );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        // ids用于按顺序保存当前页的店铺id，distanceMap记录每个店铺对应的距离
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        // 使用 ORDER BY FIELD(id, ...) 确保数据库返回顺序与Redis中按距离排序的顺序一致
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            // 将Redis查询到的距离填充回Shop实体，返回给前端展示
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
