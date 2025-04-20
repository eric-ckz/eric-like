package com.eric.like.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.eric.like.model.dto.thumb.DoThumbRequest;
import com.eric.like.model.entity.Thumb;
import jakarta.servlet.http.HttpServletRequest;

public interface ThumbService extends IService<Thumb> {

    /**
     * 点赞
     */
    Boolean doThumb(DoThumbRequest doThumbRequest, HttpServletRequest request);

    /**
     * 取消点赞
     */
    Boolean undoThumb(DoThumbRequest doThumbRequest, HttpServletRequest request);

    Boolean hasThumb(Long blogId, Long userId);

}
