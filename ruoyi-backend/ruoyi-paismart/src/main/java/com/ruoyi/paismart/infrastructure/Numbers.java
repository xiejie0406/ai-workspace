package com.ruoyi.paismart.infrastructure;
import java.math.BigDecimal;
/** 正式金额、Token与次数拒绝小数、溢出和隐式字符串转换。 */
public final class Numbers {
    private Numbers(){}
    public static long integer(Object value){
        if(!(value instanceof Number))throw new IllegalArgumentException("数值字段必须为整数");
        try{return new BigDecimal(value.toString()).longValueExact();}catch(ArithmeticException|NumberFormatException invalid){throw new IllegalArgumentException("数值字段含小数或超过整数范围");}
    }
    public static int smallInteger(Object value){try{return Math.toIntExact(integer(value));}catch(ArithmeticException invalid){throw new IllegalArgumentException("数值字段超过整数范围");}}
}
