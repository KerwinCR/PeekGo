package com.peekgo.controller;


import com.peekgo.dto.Result;
import com.peekgo.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Autowired
    private IFollowService followService;
    @PutMapping("/{followUserId}/{isFollow}")
    public Result followSomebody(@PathVariable Long followUserId,@PathVariable Boolean isFollow){
        return followService.followSomebody(followUserId,isFollow);
    }

    @GetMapping("/or/not/{followUserId}")
    public Result isFollow(@PathVariable Long followUserId){
        return followService.isFollow(followUserId);
    }
    @GetMapping("/common/{id}")
    public Result followCommon(@PathVariable Long id){
        return followService.followCommon(id);
    }
}
