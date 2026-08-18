package org.example.codesandbox.model.judge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 判题用例
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class JudgeCase {

    /**
     * 输入用例
     */
    private String inputCase;

    /**
     * 输出用例
     */
    private String outputCase;
}
