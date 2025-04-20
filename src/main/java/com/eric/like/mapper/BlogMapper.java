package com.eric.like.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eric.like.model.entity.Blog;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * @author pine
 */
public interface BlogMapper extends BaseMapper<Blog> {
    void batchUpdateThumbCount(@Param("countMap") Map<Long, Long> countMap);
}




