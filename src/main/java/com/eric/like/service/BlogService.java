package com.eric.like.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.eric.like.model.entity.Blog;
import com.eric.like.model.vo.BlogVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

public interface BlogService extends IService<Blog> {

    BlogVO getBlogVoById(Long id, HttpServletRequest request);

    List<BlogVO> getBlogVoList(List<Blog> blogList, HttpServletRequest request);

}
