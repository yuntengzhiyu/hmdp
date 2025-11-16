package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private IShopService shopService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 预热店铺数据
     */
    @Test
    public void testSaveShopToCache() throws InterruptedException {
        shopService.saveShopToRedis(1L, 10L);
    }

    /**
     * 加载店铺数据 根据GEO分类加载到redis
     */
    @Test
    public void loadShopData() throws InterruptedException {

        List<Shop> list = shopService.list();
        Map<Long, List<Shop>> collect = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        for(Map.Entry<Long, List<Shop>> entry : collect.entrySet()){
            Long typeId = entry.getKey();
            String key = RedisConstants.SHOP_GEO_KEY + typeId;
            List<Shop> shops = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();

            for(Shop shop : shops){
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }

    }
}
