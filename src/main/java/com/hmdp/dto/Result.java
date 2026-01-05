package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一响应结果类
 * 用于封装API接口的返回结果，包含成功状态、错误信息、数据和总数
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    /**
     * 请求是否成功
     */
    private Boolean success;
    /**
     * 错误信息
     */
    private String errorMsg;
    /**
     * 返回的数据
     */
    private Object data;
    /**
     * 数据总数，通常用于分页查询
     */
    private Long total;

    /**
     * 创建成功的响应结果（无数据）
     *
     * @return Result 成功的响应结果
     */
    public static Result ok(){
        return new Result(true, null, null, null);
    }

    /**
     * 创建成功的响应结果（带数据）
     *
     * @param data 返回的数据对象
     * @return Result 成功的响应结果
     */
    public static Result ok(Object data){
        return new Result(true, null, data, null);
    }

    /**
     * 创建成功的响应结果（带列表数据和总数）
     *
     * @param data 返回的数据列表
     * @param total 数据总数
     * @return Result 成功的响应结果
     */
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total);
    }

    /**
     * 创建失败的响应结果
     *
     * @param errorMsg 错误信息
     * @return Result 失败的响应结果
     */
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null);
    }
}

