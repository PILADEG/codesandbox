package org.example.codesandbox.model.judge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 判题配置
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class JudgeConfig {

    /**
     * 时间限制（ms）
     */
    private Long timeLimit;

    /**
     * 内存限制（MB）
     */
    private Long memoryLimit;
}
