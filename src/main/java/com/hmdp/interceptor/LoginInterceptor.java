package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {


    private StringRedisTemplate stringRedisTemplate;

//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1.获取session
//        HttpSession session = request.getSession();
//        //2.获取session中的用户
////        Object user = session.getAttribute("user");
//        //2.根据token获取redis中的用户
//        String token = request.getHeader("authorization");
//        if(StringUtils.isBlank(token)){
//            response.setStatus(401);
//            return false;
//        }
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
//        //Object user = stringRedisTemplate.opsForHash().get(RedisConstants.LOGIN_USER_KEY + token); get方法只能获取单个属性，不能获取整个对象
//        //3.判断用户是否存在
//        if(userMap == null){
//            return false;
//        }
//        //5.存在，保存用户信息到Threadlocal
//        //如果存在， 需要把上面的Map转换成bean
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//        UserHolder.saveUser(userDTO);
//
//        //不要忘记重新设置expire
//        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
//        //6.放行
//        return true;


        // 1.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null) {
            // 没有，需要拦截，设置状态码
            response.setStatus(401);
            // 拦截
            return false;
        }
        // 有用户，则放行
        return true;
    }
}