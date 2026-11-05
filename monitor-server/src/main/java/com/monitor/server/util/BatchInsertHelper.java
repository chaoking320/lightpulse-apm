package com.monitor.server.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 批量插入工具类 - 简化 MyBatis 批量插入操作
 * 
 * 使用示例:
 * <pre>
 * batchInsertHelper.batchInsert(metrics, 500, (mapper, metric) -> {
 *     mapper.insert(metric);
 * });
 * </pre>
 * 
 * @author LightPulse Team
 * @since 0.0.9
 */
@Slf4j
@Component
public class BatchInsertHelper {
    
    private final SqlSessionFactory sqlSessionFactory;
    
    public BatchInsertHelper(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }
    
    /**
     * 批量插入数据（推荐使用此方法）
     * 
     * @param entities 实体列表
     * @param batchSize 批次大小（推荐 500-1000）
     * @param mapperClass Mapper 接口类
     * @param consumer 插入操作（mapper, entity）
     * @param <T> 实体类型
     * @param <M> Mapper 类型
     * @return 成功插入的记录数
     */
    public <T, M> int batchInsert(List<T> entities, int batchSize, 
                                   Class<M> mapperClass, BiConsumer<M, T> consumer) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        
        long startTime = System.currentTimeMillis();
        int totalSuccess = 0;
        int totalSize = entities.size();
        
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            M mapper = session.getMapper(mapperClass);
            
            int chunkQueued = 0;
            for (int i = 0; i < totalSize; i++) {
                T entity = entities.get(i);
                
                try {
                    consumer.accept(mapper, entity);
                    chunkQueued++;
                } catch (Exception e) {
                    log.warn("[BatchInsert] 插入第 {} 条记录失败: {}", i + 1, e.getMessage());
                }
                
                // 达到批次大小或最后一条记录时提交
                if ((i + 1) % batchSize == 0 || i == totalSize - 1) {
                    // BATCH 执行器下语句在 commit/flush 时才真正发往数据库，
                    // 因此失败只会在这里暴露；之前在入队时就计为成功，失败时 totalSuccess 会虚高
                    try {
                        session.commit();
                        totalSuccess += chunkQueued;
                    } catch (Exception e) {
                        session.rollback();
                        log.error("[BatchInsert] 批次提交失败，本批 {} 条已回滚: {}", chunkQueued, e.getMessage());
                    }
                    session.clearCache();
                    chunkQueued = 0;
                }
            }
            
            long costMs = System.currentTimeMillis() - startTime;
            double successRate = totalSize > 0 ? (totalSuccess * 100.0 / totalSize) : 0;
            long tps = costMs > 0 ? (totalSuccess * 1000L / costMs) : 0;
            
            // slf4j 只识别 {}，不支持 {:.1f}，之前这里的参数会整体错位
            log.info("[BatchInsert] 批量写入完成: {}/{} 条 ({}%), 耗时: {}ms, 吞吐量: {} TPS",
                totalSuccess, totalSize, String.format("%.1f", successRate), costMs, tps);
            
        } catch (Exception e) {
            log.error("[BatchInsert] 批量插入异常", e);
        }
        
        return totalSuccess;
    }
    
    /**
     * 批量插入数据（默认批次大小 500）
     * 
     * @param entities 实体列表
     * @param mapperClass Mapper 接口类
     * @param consumer 插入操作
     * @param <T> 实体类型
     * @param <M> Mapper 类型
     * @return 成功插入的记录数
     */
    public <T, M> int batchInsert(List<T> entities, Class<M> mapperClass, 
                                   BiConsumer<M, T> consumer) {
        return batchInsert(entities, 500, mapperClass, consumer);
    }
}
