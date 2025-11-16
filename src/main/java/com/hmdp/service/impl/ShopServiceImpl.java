package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;


import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //定义一个线程池
    private static final ExecutorService excutorService = Executors.newFixedThreadPool(10);
    @Autowired
    private CacheClient cacheClient;


    //使用逻辑过期解决缓存击穿问题
    @Override
    public Result queryById(Long id) {
        //尝试从redis获取缓存
        String cache = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(cache);
        //判断是否命中
        if(StrUtil.isBlank(shopJson)) {
            return Result.fail("店铺不存在！");
        }
        //缓存命中
        //判断是否逻辑过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = BeanUtil.toBean((JSONObject)redisData.getData(), Shop.class);
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            //未过期
            return Result.ok(shop);
        }
        //逻辑过期了
        boolean isLock = trylock(RedisConstants.LOCK_SHOP_KEY + id);
        //获取锁成功， 开启独立线程进行缓存重建
        if(isLock) {
            //开启独立线程进行缓存重建
            excutorService.submit(() -> {
                try {
                    //重建缓存
                    saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        //二次检验是否缓存被重建，防止大量线程等待数据库
        shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isBlank(shopJson)) {
            //缓存未命中，直接返回失败信息
            return Result.fail("店铺数据不存在");
        }
        redisData = JSONUtil.toBean(shopJson, RedisData.class);
        shop = BeanUtil.toBean((JSONObject)redisData.getData(), Shop.class);
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            //未过期
            return Result.ok(shop);
        }
        //返回旧的数据
        return Result.ok(shop);
    }


    //使用互斥锁解决缓存击穿问题
//    @Override
//    public Result queryById(Long id) {
//        String cache = CACHE_SHOP_KEY + id;
//        // 从redis查询商铺缓存, 因为缓存穿透问题， 所以可能查询到空字符串
//        Result result = getCacheFromRedis(cache);
//        if(result != null){
//            return result;
//        }
//
//        //使用互斥锁解决缓存击穿问题
//        //缓存不存在， 到mysql中查找
//        Shop shop = null;
//        try {
//            boolean lock = trylock(RedisConstants.LOCK_SHOP_KEY + id);
//            if(!lock){
//                Thread.sleep(50);
//                return queryById(id);
//            }
//            //关键这一步还要重新判断缓存是否重建，防止堆积的线程全部请求数据库
//            result = getCacheFromRedis(cache);
//            if(result != null){
//                return result;
//            }
//
//            shop = getById(id);
//            if (shop == null) {
//                //存储空值， 解决缓存击穿问题
//                stringRedisTemplate.opsForValue().set(cache,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return Result.fail("店铺不存在！");
//            }
//            stringRedisTemplate.opsForValue().set(cache,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//            return Result.ok(shop);
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        } finally {
//            unlock(RedisConstants.LOCK_SHOP_KEY + id);
//        }
//
//    }

    public Result getCacheFromRedis(String cache){
        String strJson = stringRedisTemplate.opsForValue().get(cache);
        if(StrUtil.isNotBlank(strJson)){
            Shop shop = JSONUtil.toBean(strJson, Shop.class);
            return Result.ok(shop);
        }
        if(strJson != null){
            return Result.fail("店铺不存在！");
        }
        return null;
    }


    public boolean trylock(String key){
        //为了防止锁因为某种异常 一直不释放 设置一个有效期
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public void saveShopToRedis(Long id, Long expireTimeSeconds) throws InterruptedException {
        // 1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTimeSeconds));
        // 2.写入Redis, 因为使用逻辑过期， 所以不需要设置时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
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
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
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
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }



    //引入CacheClient后，下面的代码更方便
//    @Override
//    public Result queryById(Long id) {
//        // 调用解决缓存穿透的方法
////        Shop shop = cacheClient.handleCachePenetration(CACHE_SHOP_KEY, id, Shop.class,
////                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
////        if (Objects.isNull(shop)){
////            return Result.fail("店铺不存在");
////        }
//
//        // 调用解决缓存击穿的方法
//        Shop shop = cacheClient.handleCacheBreakdown(CACHE_SHOP_KEY, id, Shop.class,
//                this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
//        if (Objects.isNull(shop)) {
//            return Result.fail("店铺不存在");
//        }
//
//        return Result.ok(shop);
//    }
//
//    /**
//     * 更新商铺数据（采用删除缓存模式，并且采用先操作数据库，后操作缓存）
//     *
//     * @param shop
//     * @return
//     */
//    @Transactional
//    @Override
//    public Result updateShop(Shop shop) {
//        // 参数校验, 略
//
//        // 1、更新数据库中的店铺数据
//        boolean f = this.updateById(shop);
//        if (!f) {
//            // 缓存更新失败，抛出异常，事务回滚
//            throw new RuntimeException("数据库更新失败");
//        }
//        // 2、删除缓存
//        f = stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
//        if (!f) {
//            // 缓存删除失败，抛出异常，事务回滚
//            throw new RuntimeException("缓存删除失败");
//        }
//        return Result.ok();
//    }

}
