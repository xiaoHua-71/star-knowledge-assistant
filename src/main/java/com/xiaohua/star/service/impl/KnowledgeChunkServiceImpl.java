package com.xiaohua.star.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.star.mapper.KnowledgeChunkMapper;
import com.xiaohua.star.model.entity.KnowledgeChunk;
import com.xiaohua.star.service.KnowledgeChunkService;
import org.springframework.stereotype.Service;

/**
 * 切片服务实现
 */
@Service
public class KnowledgeChunkServiceImpl extends ServiceImpl<KnowledgeChunkMapper, KnowledgeChunk>
        implements KnowledgeChunkService {

}
