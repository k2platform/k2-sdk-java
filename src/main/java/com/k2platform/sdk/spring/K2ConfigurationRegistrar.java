package com.k2platform.sdk.spring;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Scans the {@link EnableK2Config} base packages for {@link K2Config} interfaces and registers a
 * {@link K2ConfigProxyFactoryBean} for each, so they can be {@code @Autowired} like any bean.
 */
public final class K2ConfigurationRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Set<String> basePackages = resolveBasePackages(metadata);
        ClassPathScanningCandidateComponentProvider scanner = interfaceScanner();

        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                register(candidate.getBeanClassName(), registry);
            }
        }
    }

    private Set<String> resolveBasePackages(AnnotationMetadata metadata) {
        Set<String> packages = new LinkedHashSet<>();
        Map<String, Object> attrs = metadata.getAnnotationAttributes(EnableK2Config.class.getName());
        if (attrs != null) {
            for (String pkg : (String[]) attrs.get("basePackages")) {
                if (StringUtils.hasText(pkg)) packages.add(pkg);
            }
            for (Class<?> clazz : (Class<?>[]) attrs.get("basePackageClasses")) {
                packages.add(ClassUtils.getPackageName(clazz));
            }
        }
        if (packages.isEmpty()) {
            packages.add(ClassUtils.getPackageName(metadata.getClassName()));
        }
        return packages;
    }

    private ClassPathScanningCandidateComponentProvider interfaceScanner() {
        // useDefaultFilters=false; accept @K2Config-annotated *interfaces* only.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isInterface()
                                && beanDefinition.getMetadata().isIndependent();
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(K2Config.class));
        return scanner;
    }

    private void register(String className, BeanDefinitionRegistry registry) {
        try {
            Class<?> configInterface = ClassUtils.forName(className, getClass().getClassLoader());
            K2Config ann = configInterface.getAnnotation(K2Config.class);
            String prefix = ann == null ? "" : ann.prefix();

            AbstractBeanDefinition definition = BeanDefinitionBuilder
                    .genericBeanDefinition(K2ConfigProxyFactoryBean.class)
                    .addConstructorArgValue(configInterface)
                    .addConstructorArgValue(prefix)
                    .getBeanDefinition();
            // The FactoryBean produces the interface type; key the bean by the interface name.
            registry.registerBeanDefinition(StringUtils.uncapitalize(configInterface.getSimpleName()), definition);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to register @K2Config interface " + className, e);
        }
    }
}
