package io.github.carpl2.tidebid.trade.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.carpl2.tidebid.trade.application.port.TradeIdGenerator;
import org.springframework.stereotype.Component;

@Component
public class MybatisPlusTradeIdGenerator implements TradeIdGenerator {
    @Override
    public long nextId() {
        return IdWorker.getId();
    }
}
