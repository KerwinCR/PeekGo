package com.peekgo.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.peekgo.dto.Result;
import com.peekgo.dto.ScrollResult;
import com.peekgo.dto.UserDTO;
import com.peekgo.entity.Blog;
import com.peekgo.entity.Follow;
import com.peekgo.entity.User;
import com.peekgo.mapper.BlogMapper;
import com.peekgo.service.IBlogService;
import com.peekgo.service.IFollowService;
import com.peekgo.service.IUserService;
import com.peekgo.utils.SystemConstants;
import com.peekgo.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        giveBlogUserInfo(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        if (UserHolder.getUser()==null){
            return Result.fail("请先登录");
        }
        Long userId = UserHolder.getUser().getId();
        //每个博客维护自身的点赞集合
        String key="blog:liked:"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score==null){
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
        }
        else {
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
        return Result.ok("点赞成功");
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> blogPage = new Page<>(current, SystemConstants.MAX_PAGE_SIZE);
        this.page(blogPage,null);
        blogPage.getRecords().stream().forEach((blog -> {
            isBlogLiked(blog);
            giveBlogUserInfo(blog);}));
        return Result.ok(blogPage.getRecords());
    }

    /**
     * 查询点赞的前五名
     * @param id
     * @return
     */
    @Override
    public Result queryWhoHasLiked(Long id) {
        String key="blog:liked:"+id;
        Set<String> originUserIds = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (originUserIds==null || originUserIds.isEmpty()){
            return Result.ok();
        }
        List<Long> modifiedUserIds = originUserIds.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", modifiedUserIds);
        //这里要注意用in条件返回的数据会按主键大小排序，与redis中的顺序有可能不符合，要用额外方法处理
        List<User> users = userService.query().in("id",modifiedUserIds).last("ORDER BY FIELD(id,"+idStr+")").list();
        List<UserDTO> userDTOS = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 保存博客到数据库，推送博客给粉丝
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        this.save(blog);
        //将blog发送到粉丝的收件箱
        //查询被关注人的id和博客发送博客者id相同的数据
        List<Follow> followList = followService.list(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, blog.getUserId()));
        followList.stream().map(Follow::getUserId).forEach((userId)->{
            String key="feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 获取收件箱的信息
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key="feed:"+userId;
        //拿到博客id和时间戳的键值对
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        ArrayList<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime=0;
        int newOffset=1;
        //通过键值对分析出blogId，新的minTime和offset
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time==minTime){
                newOffset++;
            }
            else {
                minTime=time;
                newOffset=1;
            }
        }
        //将结果封装返回给前端
        //此处将数组转成以逗号为分隔符的字符串
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = list(new LambdaQueryWrapper<Blog>().in(Blog::getId, ids).last("ORDER BY FIELD(id," + idStr + ")"));
        blogs.stream().forEach(blog -> {
            giveBlogUserInfo(blog);
            isBlogLiked(blog);
        });
        ScrollResult scrollResult = new ScrollResult(blogs, minTime, newOffset);
        return Result.ok(scrollResult);
    }

    /**
     * 判断当前用户是否已经点赞
     * @param blog
     */
    public void isBlogLiked(Blog blog){
        Long id = blog.getId();
        if (UserHolder.getUser()==null){
            return;
        }
        Long userId = UserHolder.getUser().getId();
        //每个博客维护自身的点赞集合
        String key="blog:liked:"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    /**
     * 将用户信息写进blog
     * @param blog
     */
    public void giveBlogUserInfo(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
