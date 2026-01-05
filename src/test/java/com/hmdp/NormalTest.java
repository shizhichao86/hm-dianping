package com.hmdp;

import org.junit.jupiter.api.Test;

public class NormalTest {

    @Test
    void testBitMap() {
        // 模拟某个月的签到情况：二进制1表示已签到，0表示未签到
        int i = 0b1110111111111111111111111;

        long t1 = System.nanoTime();
        int count = 0;
        while (true){
            // 方案一：通过与1做与运算，快速获取最低位bit，判断当天是否签到
            if ((i & 1) == 0){
                    break;
            }else{
                count++;
            }
            i >>>= 1;
        }
        long t2 = System.nanoTime();
        System.out.println("time1 = " + (t2 - t1));
        System.out.println("count = " + count);

        i = 0b1110111111111111111111111;
        long t3 = System.nanoTime();
        int count2 = 0;
        while (true) {
            // 方案二：通过右移再左移判断最低位是否为0（逻辑等价但略慢）
            if(i >>> 1 << 1 == i){
                // 最低位为0，代表未签到，结束
                break;
            }else{
                // 说明签到了
                count2++;
            }

            i >>>= 1;
        }
        long t4 = System.nanoTime();
        System.out.println("time2 = " + (t4 - t3));
        System.out.println("count2 = " + count2);
    }
}
