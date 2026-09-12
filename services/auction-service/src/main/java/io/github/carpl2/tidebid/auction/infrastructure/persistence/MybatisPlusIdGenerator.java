package io.github.carpl2.tidebid.auction.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.carpl2.tidebid.auction.application.port.IdGenerator;
import org.springframework.stereotype.Component;

@Component
public class MybatisPlusIdGenerator implements IdGenerator {

    @Override
    public long nextId() {
        return IdWorker.getId();
    }
}
