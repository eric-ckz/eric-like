package com.eric.ericlike.controller;

import com.eric.ericlike.common.BaseResponse;
import com.eric.ericlike.common.ResultUtils;
import com.eric.ericlike.model.entity.Blog;
import com.eric.ericlike.model.vo.BlogVO;
import com.eric.ericlike.service.BlogService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private BlogService blogService;

    @GetMapping("/get")
    public BaseResponse<BlogVO> get(Long blogId, HttpServletRequest request){
        BlogVO blogVO = blogService.getBlogVoById(blogId, request);
        return ResultUtils.success(blogVO);
    }

    @GetMapping("/list")
    public BaseResponse<List<BlogVO>> list(HttpServletRequest request) {
        List<Blog> blogList = blogService.list();
        List<BlogVO> blogVOList = blogService.getBlogVoList(blogList, request);
        return ResultUtils.success(blogVOList);
    }


}
