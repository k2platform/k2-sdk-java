package com.k2platform.sdk.spring;

import com.k2platform.sdk.K2Client;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;

import java.lang.reflect.Proxy;

/**
 * Creates the JDK dynamic proxy for one {@link K2Config} interface, backed by the application's
 * {@link K2Client} bean. Registered (one per interface) by {@link K2ConfigurationRegistrar}.
 */
public final class K2ConfigProxyFactoryBean implements FactoryBean<Object>, BeanFactoryAware {

    private final Class<?> configInterface;
    private final String prefix;
    private BeanFactory beanFactory;

    @SuppressWarnings("unused") // invoked reflectively via constructor-arg bean definition
    public K2ConfigProxyFactoryBean(Class<?> configInterface, String prefix) {
        this.configInterface = configInterface;
        this.prefix = prefix;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object getObject() {
        K2Client client = beanFactory.getBean(K2Client.class);
        return Proxy.newProxyInstance(
                configInterface.getClassLoader(),
                new Class<?>[]{configInterface},
                new K2ConfigInvocationHandler(client, prefix));
    }

    @Override
    public Class<?> getObjectType() {
        return configInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
