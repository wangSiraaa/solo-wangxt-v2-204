package com.example.craft.job;

import com.example.craft.config.CraftProperties;
import com.example.craft.domain.CraftOrder;
import com.example.craft.repo.OrderRepo;
import com.example.craft.service.CraftService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动完成 & 超时释放。
 * 与玩家手动领取共用同一套加锁事务方法：谁先抢到单据行锁谁生效，
 * 不会出现“超时释放”和“完成发奖”同时落库。
 */
@Component
public class CraftScheduler {

    private static final Logger log = LoggerFactory.getLogger(CraftScheduler.class);
    private static final int BATCH = 50;

    private final OrderRepo orderRepo;
    private final CraftService craftService;
    private final CraftProperties props;

    public CraftScheduler(OrderRepo orderRepo, CraftService craftService, CraftProperties props) {
        this.orderRepo = orderRepo;
        this.craftService = craftService;
        this.props = props;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 2000)
    public void tick() {
        if (!props.schedulerEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<CraftOrder> due = orderRepo.findAutoCompleteDue(now, BATCH);
        for (CraftOrder o : due) {
            try {
                craftService.sweepAutoComplete(o.orderNo(), now);
            } catch (Exception ex) {
                log.error("auto-complete failed order={}", o.orderNo(), ex);
            }
        }
        List<CraftOrder> expired = orderRepo.findExpired(now, BATCH);
        for (CraftOrder o : expired) {
            try {
                craftService.sweepTimeout(o.orderNo(), now);
            } catch (Exception ex) {
                log.error("timeout cancel failed order={}", o.orderNo(), ex);
            }
        }
    }
}
