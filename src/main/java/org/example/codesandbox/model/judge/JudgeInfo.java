package org.example.codesandbox.model.judge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 判题信息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class JudgeInfo {

    /**
     * 判题信息枚举（如 Accepted）
     */
    private String message;

    /**
     * 消耗时间（ms）
     */
    private Long time;

    /**
     * 消耗内存（KB）
     */
    private Long memory;
}
