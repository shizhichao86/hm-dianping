package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 用户信息持有者工具类
 * 使用ThreadLocal来存储当前线程的用户信息，确保线程安全
 */
public class UserHolder {
    /**
     * ThreadLocal变量，用于存储当前线程的用户信息
     */
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    /**
     * 保存用户信息到当前线程的ThreadLocal中
     *
     * @param user 用户信息DTO对象
     */
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    /**
     * 获取当前线程中的用户信息
     *
     * @return 当前线程中存储的用户信息DTO对象，如果未设置则返回null
     */
    public static UserDTO getUser(){
        return tl.get();
    }

    /**
     * 从当前线程中移除用户信息
     * 用于清理ThreadLocal中的数据，防止内存泄漏
     */
    public static void removeUser(){
        tl.remove();
    }
}
