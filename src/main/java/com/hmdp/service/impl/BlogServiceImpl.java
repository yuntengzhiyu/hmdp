package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IFollowService followService;

    /**
     * 根据id查询博客
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        // 查询博客信息
        Blog blog = this.getById(id);
        if (Objects.isNull(blog)){
            return Result.fail("笔记不存在");
        }
        // 查询blog相关的用户信息
        queryUserByBlog(blog);
        // 查询博客是否被点赞过
        isBLogLiked(blog);
        return Result.ok(blog);
    }

    private void isBLogLiked(Blog blog) {
        // 查询博客是否被点赞过
        UserDTO user = UserHolder.getUser();
        if(user == null)return;
        Long userId = user.getId();

        Long blogId = blog.getId();
        // 1.判断当前登录用户是否点赞
        String key = BLOG_LIKED_KEY + blogId;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(Objects.nonNull(score));
    }

//
@Override
public Result likeBlog(Long id) {
    // 判断用户是否点赞
    Long userId = UserHolder.getUser().getId();
    String key = BLOG_LIKED_KEY + id;
    Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
    boolean result;
    if (Objects.isNull(score)) {
        // 用户未点赞，点赞数+1
        result = this.update(new LambdaUpdateWrapper<Blog>()
                .eq(Blog::getId, id)
                .setSql("liked = liked + 1"));
        if (result) {
            // 数据库更新成功，更新缓存
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }
    } else {

        // 用户已点赞，点赞数-1
        result = this.update(new LambdaUpdateWrapper<Blog>()
                .eq(Blog::getId, id)
                .setSql("liked = liked - 1"));
        if (result) {
            // 数据更新成功，更新缓存
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
    }
    return Result.ok();
}
    /**
     * 查询热门博客
     *
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach((blog) -> {
            queryUserByBlog(blog);
            // 查询博客是否被点赞过
            isBLogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查询博客相关用户信息
     * @param blog
     */
    private void queryUserByBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 查询博客前几名的点赞情况
     *
     * @param blogId
     * @return
     */
    public Result queryBlogLikes(Long blogId) {
        Set<String> set = stringRedisTemplate.opsForZSet()
                .range(BLOG_LIKED_KEY + blogId, 0, 4);
        if (set == null || set.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //把这个String集合转变成List<Long>

        List<Long> ids = set.stream().map(Long::valueOf).collect(Collectors.toList());
        String str = StrUtil.join(",", ids);
        List<UserDTO> list = userService.query().in("id",ids).last("ORDER BY FIELD(id," + str + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(list);



    }

    @Override
    public Result saveBlog(Blog blog) {
        //获取登录用户
        UserDTO user = UserHolder.getUser();
        //保存到数据库
        blog.setUserId(user.getId());
        boolean isSuccess = this.save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            Long userId = follow.getUserId();
            // 4.2.推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }

    /**
     * 查询当前用户关注的人的所有更新的博客
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        //查询收件箱 ZREVRANGEBYSCORE key max min LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //获取下一次分页的参数
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        Long minTime = 0L;
        Integer os = 0;
        List<Long> ids = new ArrayList<>();
        for(ZSetOperations.TypedTuple<String> tuple : typedTuples){
            long time = tuple.getScore().longValue();
            ids.add(Long.valueOf(tuple.getValue()));
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }

        //根据id查询博客
        String str = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + str + ")").list();
        for(Blog blog : blogs){
            //查询博客相关的用户
            queryUserByBlog(blog);
            //查询博客是否被点赞过
            isBLogLiked(blog);
        }
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
