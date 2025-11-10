package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private IShopService shopService;
    /**
     * 预热店铺数据
     */
    @Test
    public void testSaveShopToCache() throws InterruptedException {
        shopService.saveShopToRedis(1L, 10L);
    }

}
