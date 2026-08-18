package org.example.codesandbox.model.judge.execute;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.codesandbox.model.judge.JudgeCase;
import org.example.codesandbox.model.judge.JudgeConfig;
import org.example.codesandbox.model.judge.JudgeInfo;

import java.util.List;
import java.io.Serializable;
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExecuteMessage implements Serializable {

    private String message;

    private String errorMessage;

    private String language;

    private String code;

    private Long time;

    private Long memory;

    private List<JudgeCase> judgeCases;

    private JudgeConfig  judgeConfig;

    private static final long serialVersionUID = 1L;
}

