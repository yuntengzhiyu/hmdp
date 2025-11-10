package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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


    @Override
    public Result queryById(Long id) {
        String cache = CACHE_SHOP_KEY + id;
        // 从redis查询商铺缓存, 因为缓存穿透问题， 所以可能查询到空字符串
        Result result = getCacheFromRedis(cache);
        if(result != null){
            return result;
        }

        //缓存不存在， 到mysql中查找
        Shop shop = null;
        try {
            boolean lock = trylock(RedisConstants.LOCK_SHOP_KEY + id);
            if(!lock){
                Thread.sleep(50);
                return queryById(id);
            }
            //关键这一步还要重新判断缓存是否重建，防止堆积的线程全部请求数据库
            result = getCacheFromRedis(cache);
            if(result != null){
                return result;
            }

            shop = getById(id);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(cache,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在！");
            }
            stringRedisTemplate.opsForValue().set(cache,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return Result.ok(shop);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unlock(RedisConstants.LOCK_SHOP_KEY + id);
        }

    }

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

}
