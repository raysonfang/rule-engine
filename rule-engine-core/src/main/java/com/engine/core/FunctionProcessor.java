/**
 * Copyright (c) 2020 dingqianwen (761945125@qq.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.engine.core;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import com.engine.core.annotation.Executor;
import com.engine.core.exception.FunctionException;
import com.engine.core.annotation.FailureStrategy;
import com.engine.core.annotation.Param;
import com.engine.core.exception.ValidException;
import com.engine.core.exception.ValueException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import javax.validation.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 〈一句话功能简述〉<br>
 * 〈〉
 *
 * @author dingqianwen
 * @date 2020/7/19
 * @since 1.0.0
 */
@Slf4j
public class FunctionProcessor {

    /**
     * 参数绑定用，基本数据类型绑定解析
     */
    private static final Set<Class<?>> BASIC_TYPE = new HashSet<Class<?>>() {
        {
            add(String.class);
            add(Integer.class);
            add(BigDecimal.class);
            add(Boolean.class);
        }
    };

    /**
     * 函数执行器
     *
     * @param abstractFunction 执行的函数
     * @param paramMap         函数入参
     * @return 函数执行结果
     */
    public Object executor(Object abstractFunction, Map<String, Object> paramMap) {
        Method executor = null;
        Method failureStrategy = null;
        Method[] methods = abstractFunction.getClass().getDeclaredMethods();
        for (Method method : methods) {
            if (method.isAnnotationPresent(Executor.class)) {
                //如果已经存在，则抛出异常
                if (executor != null) {
                    throw new FunctionException("函数中存在多个@Executor");
                }
                executor = method;
            } else if (method.isAnnotationPresent(FailureStrategy.class)) {
                //如果已经存在，则抛出异常
                if (failureStrategy != null) {
                    throw new FunctionException("函数中存在多个@FailureStrategy");
                }
                failureStrategy = method;
            }
        }
        String functionName = abstractFunction.getClass().getSimpleName();
        if (executor == null) {
            throw new FunctionException("{}中没有找到可执行函数方法", functionName);
        }
        if (failureStrategy != null && !executor.getReturnType().equals(failureStrategy.getReturnType())) {
            throw new FunctionException("失败策略方法与函数主方法返回值不一致，函数主方法返回值类型{},失败策略方法返回值类型{}", executor.getReturnType(), failureStrategy.getReturnType());
        }
        Executor annotation = executor.getAnnotation(Executor.class);
        Object[] args = getBindArgs(executor.getParameters(), paramMap);
        try {
            int maxAttempts = annotation.maxAttempts();
            int i = 0;
            do {
                if (!Modifier.isPublic(executor.getModifiers())) {
                    executor.setAccessible(true);
                }
                try {
                    return executor.invoke(abstractFunction, args);
                } catch (IllegalAccessException e) {
                    throw new FunctionException("主函数方法非法访问异常{}", e.getMessage());
                } catch (Exception e) {
                    //当重试全部用完后，还是失败，则抛出异常
                    if (i == maxAttempts || maxAttempts < 0) {
                        throw e;
                    }
                    log.warn("执行函数主方法异常，{}ms后重试调用，异常原因：{}", annotation.delay(), e);
                    ThreadUtil.sleep(annotation.delay());
                }
                i++;
            } while (i <= maxAttempts);
            throw new FunctionException("{} Function execution failed", functionName);
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            log.warn("函数主方法执行失败", targetException);
            //如果存在失败策略方法
            if (failureStrategy != null) {
                Class<? extends Throwable>[] noFailureFor = annotation.noFailureFor();
                //如果遇到这种类型的异常，都是直接抛出的
                for (Class<? extends Throwable> aClass : noFailureFor) {
                    if (aClass.isAssignableFrom(targetException.getClass())) {
                        throw new FunctionException(targetException);
                    }
                }
                //再判断是否满足失败函数触发的异常
                Class<? extends Throwable>[] failureFor = annotation.failureFor();
                for (Class<? extends Throwable> aClass : failureFor) {
                    if (aClass.isAssignableFrom(targetException.getClass())) {
                        log.info("开始执行函数失败策略方法");
                        if (!Modifier.isPublic(failureStrategy.getModifiers())) {
                            failureStrategy.setAccessible(true);
                        }
                        try {
                            return failureStrategy.invoke(abstractFunction, getBindArgs(failureStrategy.getParameters(), paramMap));
                        } catch (IllegalAccessException ex) {
                            throw new FunctionException("失败策略方法非法访问异常{}", ex.getMessage());
                        } catch (InvocationTargetException ex) {
                            log.error("失败策略方法执行失败", ex.getTargetException());
                            throw new FunctionException(ex.getTargetException());
                        }
                    }
                }
                log.warn("失败策略方法异常未命中执行");
            }
            throw new FunctionException(targetException);
        }

    }

    /**
     * 获取绑定参数
     *
     * @param parameters 方法参数列表
     * @param paramMap   执行入参
     * @return 绑定后的参数列表
     */
    @SneakyThrows
    private Object[] getBindArgs(Parameter[] parameters, Map<String, Object> paramMap) {
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            Class<?> parameterType = parameter.getType();
            if (Map.class.isAssignableFrom(parameterType)) {
                Type parameterParameterizedType = parameter.getParameterizedType();
                if (parameterParameterizedType instanceof ParameterizedType) {
                    ParameterizedType parameterizedType = (ParameterizedType) parameterParameterizedType;
                    Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                    if (!(actualTypeArguments[0].equals(String.class) && actualTypeArguments[1].equals(Object.class))) {
                        throw new ValidException("仅支持范型为<String,Object>类型Map");
                    }
                }
                args[i] = paramMap;
            } else if (BASIC_TYPE.contains(parameterType)) {
                Object value = paramMap.get(getParameterName(parameter));
                this.paramValid(parameter, value);
                Object instance = parameterType.getConstructor(String.class).newInstance(String.valueOf(value));
                args[i] = Optional.ofNullable(value).map(m -> instance).orElse(null);
            } else if (List.class.isAssignableFrom(parameterType) || Set.class.isAssignableFrom(parameterType)) {
                Type parameterParameterizedType = parameter.getParameterizedType();
                Collection<?> value = (Collection<?>) paramMap.get(getParameterName(parameter));
                // 校验集合参数
                this.paramValid(parameter, value);
                // bug 修复，空集合参数导致空指针问题
                if (value != null) {
                    Stream<?> stream = value.stream();
                    if (parameterParameterizedType instanceof ParameterizedType) {
                        ParameterizedType parameterizedType = (ParameterizedType) parameterParameterizedType;
                        Type typeArgument = parameterizedType.getActualTypeArguments()[0];
                        // 判断方法集合类型
                        if (typeArgument.equals(BigDecimal.class)) {
                            stream = stream.map(m -> new BigDecimal(String.valueOf(m)));
                        } else if (typeArgument.equals(Integer.class)) {
                            stream = stream.map(m -> new Integer(String.valueOf(m)));
                        }
                    }
                    if (Set.class.isAssignableFrom(parameterType)) {
                        args[i] = stream.collect(Collectors.toSet());
                    } else {
                        args[i] = stream.collect(Collectors.toList());
                    }
                }
            } else {
                Constructor<?> constructor = parameterType.getConstructor();
                if (!Modifier.isPublic(constructor.getModifiers())) {
                    constructor.setAccessible(true);
                }
                Object newInstance = constructor.newInstance();
                BeanUtil.copyProperties(paramMap, newInstance);
                //如果参数前面有Valid注解
                if (parameter.isAnnotationPresent(Valid.class)) {
                    //参数校验,带有注解的属性校验
                    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
                    //验证某个对象,，其实也可以只验证其中的某一个属性的
                    Set<ConstraintViolation<Object>> constraintViolations = validator.validate(newInstance);
                    Iterator<ConstraintViolation<Object>> iter = constraintViolations.iterator();
                    if (iter.hasNext()) {
                        ConstraintViolation<Object> next = iter.next();
                        throw new ValueException(next.getPropertyPath().toString() + next.getMessage());
                    }
                }
                args[i] = newInstance;
            }
        }
        return args;
    }

    /**
     * 获取方法参数名称，如果存在Param此注解并且注解value不为空，则返回注解value,否则直接返回参数的name
     *
     * @param parameter 参数信息
     * @return 参数名称
     */
    private String getParameterName(Parameter parameter) {
        if (parameter.isAnnotationPresent(Param.class)) {
            Param param = parameter.getAnnotation(Param.class);
            return StrUtil.isBlank(param.value()) ? parameter.getName() : param.value();
        }
        return parameter.getName();
    }

    /**
     * 校验普通参数
     *
     * @param parameter 参数信息
     * @param value     参数值
     */
    private void paramValid(Parameter parameter, Object value) {
        String name = getParameterName(parameter);
        if (parameter.isAnnotationPresent(Param.class)) {
            Param param = parameter.getAnnotation(Param.class);
            if (!param.required()) {
                return;
            }
            if (Objects.isNull(value)) {
                throw new ValidException("{} can not be null", name);
            }
        }
    }

}
