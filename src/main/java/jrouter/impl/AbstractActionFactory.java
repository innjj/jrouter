/*
 * Copyright (C) 2010-2111 sunjumper@163.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package jrouter.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jrouter.ActionFactory;
import jrouter.ActionFilter;
import jrouter.ConverterFactory;
import jrouter.JRouterException;
import jrouter.MethodInvokerFactory;
import jrouter.ObjectFactory;
import jrouter.annotation.Action;
import jrouter.annotation.Ignore;
import jrouter.annotation.Interceptor;
import jrouter.annotation.InterceptorStack;
import jrouter.annotation.Result;
import jrouter.annotation.ResultType;
import jrouter.bytecode.javassist.JavassistMethodChecker;
import jrouter.bytecode.javassist.JavassistMethodInvokerFactory;
import jrouter.util.ClassUtil;
import jrouter.util.MethodUtil;
import jrouter.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提供常用属性/公共组件的基础{@code ActionFactory}抽象类型；
 * 提供了创建/新增{@link Interceptor}拦截器、{@link InterceptorStack}拦截栈、
 * {@link ResultType}结果类型、{@link Result}结果对象及的集合的方法。
 *
 * <p>
 * 提供默认的{@code ConverterFactory}实现{@link MultiParameterConverterFactory}。
 * </p>
 * <p>
 * 提供默认的{@code MethodInvokerFactory}实现{@link JavassistMethodInvokerFactory}。
 * </p>
 * <p>
 * 提供默认的{@code ObjectFactory}实现{@link DefaultObjectFactory}。
 * </p>
 * <p>
 * 提供默认的方法检查器{@link JavassistMethodChecker}。
 * </p>
 *
 * @param <K> 调用{@link Action}的标识。
 */
public abstract class AbstractActionFactory<K> implements ActionFactory<K> {

    /** 日志 */
    private static final Logger LOG = LoggerFactory.getLogger(AbstractActionFactory.class);

    /**
     * 创建对象的工厂对象。
     */
    @lombok.Getter
    private ObjectFactory objectFactory = null;

    /**
     * 创建底层方法代理的工厂对象。
     */
    @lombok.Getter
    private MethodInvokerFactory methodInvokerFactory = null;

    /**
     * 创建底层方法转换器的工厂对象。
     */
    @lombok.Getter
    private ConverterFactory converterFactory = null;

    /**
     * 方法检查器。
     */
    @lombok.Getter
    private JavassistMethodChecker methodChecker;

    /**
     * ActionFilter接口。
     *
     * @since 1.7.4
     */
    @lombok.Getter
    private ActionFilter actionFilter;
////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 拦截器。
     */
    @lombok.Getter
    private final Map<String, InterceptorProxy> interceptors;

    /**
     * 拦截栈。
     */
    @lombok.Getter
    private final Map<String, InterceptorStackProxy> interceptorStacks;

    /**
     * 结果类型。
     */
    @lombok.Getter
    private final Map<String, ResultTypeProxy> resultTypes;

    /**
     * 默认的全局结果对象集合。
     */
    @lombok.Getter
    private final Map<String, ResultProxy> results;

    /**
     * 根据指定的键值映射构造初始化数据的ActionFactory对象。
     *
     * @param properties 指定的初始化数据键值映射。
     */
    public AbstractActionFactory(Map<String, Object> properties) {
        //initiate properties
        setActionFactoryProperties(properties);
        interceptors = new HashMap<>();
        interceptorStacks = new HashMap<>();
        resultTypes = new HashMap<>();
        results = new HashMap<>();
    }

    /**
     * 设置ActionFactory初始化属性值。
     *
     * @param properties 属性值键值映射。
     */
    private void setActionFactoryProperties(Map<String, Object> properties) {
        boolean setBytecode = false;

        //load objectFactory first
        Object value = properties.get("objectFactory");
        if (value != null) {
            String strValue = value.toString().trim();
            if (value instanceof String && StringUtil.isNotBlank(strValue)) {
                try {
                    this.objectFactory = (ObjectFactory) (DefaultObjectFactory._newInstance(ClassUtil.loadClass(strValue)));
                } catch (ClassNotFoundException ex) {
                    throw new JRouterException(ex);
                }
            } else if (value instanceof Class) {
                this.objectFactory = (ObjectFactory) (DefaultObjectFactory._newInstance((Class) value));
            } else {
                //设置创建对象的工厂对象
                this.objectFactory = (ObjectFactory) value; //throw exception if not matched
            }
            LOG.info("Set objectFactory : {}", this.objectFactory);
        }
        //create default objectFactory
        if (objectFactory == null) {
            objectFactory = createDefaultObjectFactory();
            LOG.info("No objectFactory setting, use default : {}", objectFactory);
        }

        //all properties
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            String name = e.getKey();
            Object val = e.getValue();
            if (val == null) {
                LOG.warn("Ingore null value Property [{}].", name);
                continue;
            }
            //string value
            String strValue = val.toString().trim();
            if ("bytecode".equalsIgnoreCase(name)) {
                setBytecode = true;
                if (val instanceof String && StringUtil.isNotBlank(strValue)) {
                    //default to use java reflect directly
                    if ("default".equalsIgnoreCase(strValue)) {
                        methodInvokerFactory = null;
                        LOG.info("Set methodInvokerFactory : " + strValue);
                    } else if ("javassist".equalsIgnoreCase(strValue)) {
                        methodInvokerFactory = new JavassistMethodInvokerFactory();
                        LOG.info("Set methodInvokerFactory : {}", this.methodInvokerFactory);
                    } else {
                        setBytecode = false;
                        LOG.warn("Unknown bytecode property : {}", strValue);
                    }
                } else {
                    //throw exception if not matched
                    methodInvokerFactory = (MethodInvokerFactory) val;
                    LOG.info("Set methodInvokerFactory : {}", this.methodInvokerFactory);
                }
            } else if ("converterFactory".equalsIgnoreCase(name)) {
                converterFactory = loadComponent(ConverterFactory.class, val);
                LOG.info("Set converterFactory : {}", this.converterFactory);
            } else if ("interceptorMethodChecker".equalsIgnoreCase(name)) {
                //create interceptorMethodChecker
                if (ClassUtil.isJavassistSupported() && StringUtil.isNotBlank(strValue)) {
                    methodChecker = new JavassistMethodChecker(strValue);
                    LOG.info("Set methodChecker : {}", this.methodChecker);
                }
            } else if ("actionFilter".equalsIgnoreCase(name)) {
                actionFilter = loadComponent(ActionFilter.class, val);
                LOG.info("Set actionFilter : {}", this.actionFilter);
            }
        }
        //create default actionFilter
        if (actionFilter == null) {
            actionFilter = createDefaultActionFilter();
            LOG.info("No actionFilter setting, use default : {}", actionFilter);
        }
        //create default methodInvokerFactory
        if (!setBytecode) {
            if (methodInvokerFactory == null) {
                methodInvokerFactory = createDefaultMethodInvokerFactory();
            }
        }
        //create default converterFactory
        if (converterFactory == null) {
            converterFactory = createDefaultConverterFactory();
            LOG.info("No converterFactory setting, use default : {}", converterFactory);
        }
    }

    /**
     * {@code ObjectFactory}初始化完成后提供便捷地加载所需组件.
     *
     * @param <T> 所加载组件的类型。
     * @param componentClass 所加载组件的类型。
     * @param value 所加载组件的值。
     *
     * @return 已加载组件的值。
     *
     * @see #getObjectFactory()
     */
    protected <T> T loadComponent(Class<T> componentClass, Object value) {
        if (value instanceof String) {
            if (StringUtil.isNotBlank((String) value)) {
                try {
                    return objectFactory.newInstance((Class<T>) ClassUtil.loadClass((String) value));
                } catch (ClassNotFoundException ex) {
                    LOG.error("Can't set {} of class : {}", componentClass, value);
                    throw new JRouterException(ex);
                }
            }
        } else if (value instanceof Class) {
            return objectFactory.newInstance((Class<T>) value);
        } else {
            //throw exception if not matched
            return (T) value;
        }
        return null;
    }

    /**
     * 未设置 objectFactory 属性时，提供默认的{@code ObjectFactory}实现。
     * 默认提供{@link DefaultObjectFactory}。
     *
     * @return ObjectFactory对象。
     */
    protected ObjectFactory createDefaultObjectFactory() {
        return new DefaultObjectFactory();
    }

    /**
     * 未设置 actionFilter 属性时，提供默认的{@code ActionFilter}实现。
     * 默认提供{@link DefaultActionFilter}。
     *
     * @return ActionFilter对象。
     */
    protected ActionFilter createDefaultActionFilter() {
        return new DefaultActionFilter();
    }

    /**
     * 未设置 proxyFactory 属性时，提供默认的{code MethodInvokerFactory}实现。
     * 默认引入javassist时提供{@link JavassistMethodInvokerFactory}；若无javassist引用则采用java反射机制。
     *
     * @see DefaultProxy#invoke
     */
    private MethodInvokerFactory createDefaultMethodInvokerFactory() {
        //check if javassist is supported
        if (ClassUtil.isJavassistSupported()) {
            JavassistMethodInvokerFactory javassistMethodInvokerFactory = new JavassistMethodInvokerFactory();
            LOG.info("No methodInvokerFactory setting, use javassist as default : {}", javassistMethodInvokerFactory);
            return javassistMethodInvokerFactory;
        } else {
            LOG.info("No methodInvokerFactory setting and no javassist jar found, use java reflect as default");
            return null;
        }
    }

    /**
     * 未设置 converterFactory 属性时提供默认的{@code ConverterFactory}实现。
     * 默认提供{@link MultiParameterConverterFactory}。
     *
     * @return ConverterFactory object.
     */
    protected ConverterFactory createDefaultConverterFactory() {
        return new MultiParameterConverterFactory(true);
    }

    @Override
    public void clear() {
        interceptorStacks.clear();
        interceptors.clear();
        resultTypes.clear();
        results.clear();
    }
////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 添加拦截器。
     *
     * @param ip 拦截器代理对象。
     */
    public void addInterceptor(InterceptorProxy ip) {
        String name = ip.getName();

        if (StringUtil.isBlank(name))
            throw new IllegalArgumentException("Null name of Interceptor : " + ip.getMethodInfo());

        if (interceptors.containsKey(name)) {
            throw new JRouterException("Duplicate Interceptor [" + name + "] : "
                    + ip.getMethodInfo() + " override "
                    + interceptors.get(name).getMethodInfo());
        } else if (LOG.isInfoEnabled()) {
            LOG.info("Add Interceptor [{}] at : {} ", name, ip.getMethodInfo());
        }
        interceptors.put(name, ip);
    }

    /**
     * 添加拦截器。
     *
     * @param obj 包含{@link Interceptor}注解的类或实例对象。
     *
     * @see jrouter.annotation.Interceptor
     */
    public void addInterceptors(Object obj) {
        boolean isCls = obj instanceof Class;
        Class<?> cls = isCls ? (Class) obj : objectFactory.getClass(obj);
        Object invoker = isCls ? null : obj;
        Method[] ms = cls.getDeclaredMethods();
        for (Method m : ms) {
            int mod = m.getModifiers();
            //带@Interceptor的public/protected方法
            if ((Modifier.isPublic(mod) || Modifier.isProtected(mod))
                    && m.isAnnotationPresent(Interceptor.class)) {
                if (m.isAnnotationPresent(Ignore.class)) {
                    if (LOG.isInfoEnabled()) {
                        LOG.info("Ignore Interceptor : {}", MethodUtil.getMethod(m));
                    }
                    continue;
                }
                m.setAccessible(true);
                //static method
                if (Modifier.isStatic(mod)) {
                    addInterceptor(createInterceptorProxy(m, null));
                } else {
                    //为类对象且调用者为 null
                    if (isCls && invoker == null) {
                        invoker = objectFactory.newInstance(cls);
                    }
                    //the same object
                    addInterceptor(createInterceptorProxy(m, invoker));
                }
            }
        }
    }

    /**
     * 添加拦截栈。
     *
     * @param isp 拦截栈代理对象。
     */
    public void addInterceptorStack(InterceptorStackProxy isp) {
        String name = isp.getName();
        if (StringUtil.isBlank(name))
            throw new IllegalArgumentException("Null name of InterceptorStack : " + isp.getFieldName());

        if (interceptorStacks.containsKey(name)) {
            throw new JRouterException("Duplicate InterceptorStack [" + name + "] : "
                    + isp.getFieldName() + " override "
                    + interceptorStacks.get(name).getFieldName());
        } else if (LOG.isInfoEnabled()) {
            LOG.info("Add InterceptorStack [{}] : {}", name, isp);
        }
        interceptorStacks.put(name, isp);
    }

    /**
     * 添加拦截栈。
     *
     * @param obj 包含{@link InterceptorStack}注解的类或实例对象。
     *
     * @see jrouter.annotation.InterceptorStack
     */
    public void addInterceptorStacks(Object obj) {
        boolean isCls = obj instanceof Class;
        Class<?> cls = isCls ? (Class) obj : objectFactory.getClass(obj);
        Object invoker = isCls ? null : obj;
        Field[] fs = cls.getDeclaredFields();
        //TODO 是否成员变量
        for (Field f : fs) {
            int mod = f.getModifiers();
            //带@InterceptorStack的public属性
            if (Modifier.isPublic(mod) && f.isAnnotationPresent(InterceptorStack.class)) {
                f.setAccessible(true);
                try {
                    //static field
                    if (Modifier.isStatic(mod)) {
                        addInterceptorStack(createInterceptorStackProxy(f, null));
                    } else {
                        //为类对象且调用者为 null
                        if (isCls && invoker == null) {
                            invoker = objectFactory.newInstance(cls);
                        }
                        //the same object
                        addInterceptorStack(createInterceptorStackProxy(f, invoker));
                    }
                } catch (IllegalAccessException e) {
                    throw new JRouterException(e);
                }
            }
        }
    }

////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * 添加结果类型。
     *
     * @param rtp 结果类型的代理对象。
     */
    public void addResultType(ResultTypeProxy rtp) {
        String type = rtp.getType();
        if (StringUtil.isBlank(type))
            throw new IllegalArgumentException("Null type of ResultType : " + rtp.getMethodInfo());

        if (resultTypes.containsKey(type)) {
            throw new JRouterException("Duplicate ResultType [" + type + "] : "
                    + rtp.getMethodInfo() + " override "
                    + resultTypes.get(type).getMethodInfo());
        } else if (LOG.isInfoEnabled()) {
            LOG.info("Add ResultType [{}] at : {}", type, rtp.getMethodInfo());
        }
        resultTypes.put(type, rtp);
    }

    /**
     * 添加结果类型。
     *
     * @param obj 包含{@link ResultType}注解的类或实例对象。
     *
     * @see jrouter.annotation.ResultType
     */
    public void addResultTypes(Object obj) {
        boolean isCls = obj instanceof Class;
        Class<?> cls = isCls ? (Class) obj : objectFactory.getClass(obj);
        Object invoker = isCls ? null : obj;
        Method[] ms = cls.getDeclaredMethods();
        for (Method m : ms) {
            int mod = m.getModifiers();
            //带@ResultType的public/protected方法
            if ((Modifier.isPublic(mod) || Modifier.isProtected(mod))
                    && m.isAnnotationPresent(ResultType.class)) {
                if (m.isAnnotationPresent(Ignore.class)) {
                    if (LOG.isInfoEnabled()) {
                        LOG.info("Ignore ResultType : {}", MethodUtil.getMethod(m));
                    }
                    continue;
                }
                m.setAccessible(true);
                //static method
                if (Modifier.isStatic(mod)) {
                    addResultType(createResultTypeProxy(m, null));
                } else {
                    //为类对象且调用者为 null
                    if (isCls && invoker == null) {
                        invoker = objectFactory.newInstance(cls);
                    }
                    //the same object
                    addResultType(createResultTypeProxy(m, invoker));
                }
            }
        }
    }

    /**
     * 添加结果对象。
     *
     * @param rp 结果对象的代理对象。
     */
    public void addResult(ResultProxy rp) {
        String name = rp.getResult().name();
        if (StringUtil.isBlank(name))
            throw new IllegalArgumentException("Null name of Result : " + rp.getMethodInfo());

        if (results.containsKey(name)) {
            throw new JRouterException("Duplicate Result [" + name + "] : "
                    + rp.getMethodInfo() + " override "
                    + results.get(name).getMethodInfo());
        } else if (LOG.isInfoEnabled()) {
            LOG.info("Add Result [{}] : {}", name, rp.getMethodInfo());
        }
        results.put(name, rp);
    }

    /**
     * 添加全局结果对象。
     *
     * @param obj 包含{@link Result}注解的类或实例对象。
     *
     * @see jrouter.annotation.Result
     */
    public void addResults(Object obj) {
        boolean isCls = obj instanceof Class;
        Class<?> cls = isCls ? (Class) obj : objectFactory.getClass(obj);
        Object invoker = isCls ? null : obj;
        Method[] ms = cls.getDeclaredMethods();
        for (Method m : ms) {
            int mod = m.getModifiers();
            //带@Result的public/protected方法
            if ((Modifier.isPublic(mod) || Modifier.isProtected(mod))
                    && m.isAnnotationPresent(Result.class)) {
                if (m.isAnnotationPresent(Ignore.class)) {
                    if (LOG.isInfoEnabled()) {
                        LOG.info("Ignore Result : {}", MethodUtil.getMethod(m));
                    }
                    continue;
                }
                m.setAccessible(true);
                //static method
                if (Modifier.isStatic(mod)) {
                    addResult(createResultProxy(m, null));
                } else {
                    //为类对象且调用者为 null
                    if (isCls && invoker == null) {
                        invoker = objectFactory.newInstance(cls);
                    }
                    //the same object
                    addResult(createResultProxy(m, invoker));
                }
            }
        }
    }

////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * 创建Interceptor代理对象。
     *
     * @param method 指定的方法。
     * @param obj 方法所在的对象。
     *
     * @return Interceptor代理对象。
     */
    private InterceptorProxy createInterceptorProxy(Method method, Object obj) {
        //do interceptor method check
        if (methodChecker != null) {
            methodChecker.check(method);
        }
        Interceptor interceptor = method.getAnnotation(Interceptor.class);
        return new InterceptorProxy(this, interceptor, method, obj);
    }

    /**
     * 创建InterceptorStack代理对象
     *
     * @param field 指定的字段。
     * @param obj 字段所在对象。
     *
     * @return InterceptorStack代理对象。
     *
     * @throws IllegalAccessException 如果调用的对象无法访问指定字段。
     */
    private InterceptorStackProxy createInterceptorStackProxy(Field field, Object obj) throws IllegalAccessException {
        InterceptorStack interceptorStack = field.getAnnotation(InterceptorStack.class);
        String name = interceptorStack.name().trim();

        //interceptorStack name
        //未指定拦截栈名称则取字符串的值为名称
        if (StringUtil.isEmpty(name)) {
            name = field.get(obj).toString();
            //空命名异常
            if (StringUtil.isEmpty(name))
                throw new IllegalArgumentException("Null name of InterceptorStack : "
                        + field.getName() + " at " + objectFactory.getClass(obj));
        }
        //interceptors name
        String[] names = interceptorStack.interceptors();

        List<InterceptorProxy> list = null;
        if (names != null) {
            list = new ArrayList<>(names.length);
            //add interceptorStack
            //for (int i = names.length - 1; i >= 0; i--) {
            for (int i = 0; i < names.length; i++) {
                InterceptorProxy ip = interceptors.get(names[i]);
                //if null
                if (ip == null) {
                    LOG.warn("No such Interceptor [{}] for : {}", names[i], field);
                } else {
                    list.add(ip);
                }
            }
        }
        return new InterceptorStackProxy(name, field, list);
    }

    /**
     * 创建ResultType代理对象。
     *
     * @param method 指定的方法。
     * @param obj 方法所在的对象。
     *
     * @return ResultType代理对象。
     */
    private ResultTypeProxy createResultTypeProxy(Method method, Object obj) {
        ResultType resultType = method.getAnnotation(ResultType.class);
        return new ResultTypeProxy(this, resultType, method, obj);
    }

    /**
     * 创建Result代理对象。
     *
     * @param method 指定的方法。
     * @param obj 方法所在的对象。
     *
     * @return Result代理对象
     */
    private ResultProxy createResultProxy(Method method, Object obj) {
        Result res = method.getAnnotation(Result.class);
        return new ResultProxy(this, res, method, obj);
    }
////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 默认创建对象的工厂类。
     */
    protected static class DefaultObjectFactory implements ObjectFactory {

        @Override
        public <T> T newInstance(Class<T> clazz) {
            return _newInstance(clazz);
        }

        //default new object with empty construction method
        private static <T> T _newInstance(Class<T> clazz) {
            try {
                return clazz.newInstance();
            } catch (IllegalAccessException | InstantiationException e) {
                throw new JRouterException(e);
            }
        }

        @Override
        public Class<?> getClass(Object obj) {
            return obj.getClass();
        }
    }

    /**
     * 默认ActionFilter实现。
     */
    protected static class DefaultActionFilter implements ActionFilter {

        @Override
        public boolean accept(Method method) {
            return method.isAnnotationPresent(Action.class);
        }

        @Override
        public Action getAnnotation(Method method) {
            return method.getAnnotation(Action.class);
        }
    }
}
