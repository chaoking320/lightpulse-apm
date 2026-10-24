package com.monitor.agent.transformer;

import com.monitor.agent.config.AgentConfig;
import com.monitor.agent.config.AgentConfig.CaptureConfig;
import com.monitor.agent.interceptor.ControllerInterceptor;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;

/**
 * ByteBuddy Transformer —— 根据服务端下发的采集策略动态构建拦截规则
 * 策略类型:
 *   - annotation:  拦截指定注解标注的类中的指定注解标注的方法
 *   - class_name:  拦截类名匹配指定模式(%通配符)的类的全部方法
 *   - package_name:拦截包名匹配指定模式(%通配符)的包下的全部方法
 */
public class MonitorTransformer {

    private final AgentConfig config;

    public MonitorTransformer(AgentConfig config) {
        this.config = config;
    }

    public ResettableClassFileTransformer install(Instrumentation inst) {
        return buildTransformer().installOn(inst);
    }

    /**
     * 热更新 —— 撤销旧 transformer，用当前策略重新安装
     * @return 新的 transformer
     */
    public ResettableClassFileTransformer reinstall(Instrumentation inst, ResettableClassFileTransformer oldTransformer) {
        if (oldTransformer != null) {
            oldTransformer.reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            System.out.println("[Monitor Agent] 已撤销旧 transformer");
        }
        ResettableClassFileTransformer newTf = install(inst);
        System.out.println("[Monitor Agent] 热更新完成, 策略数=" + config.getCaptureConfigs().size());
        return newTf;
    }

    /**
     * 根据当前策略构建 AgentBuilder（不含安装）
     */
    private AgentBuilder buildTransformer() {
        List<CaptureConfig> captures = config.getCaptureConfigs();

        ElementMatcher.Junction<TypeDescription> classMatcher = null;
        ElementMatcher.Junction<MethodDescription> methodMatcher = null;

        if (captures.isEmpty()) {
            // 默认：若策略列表为空（ConfigFetcher 还没拉到），使用一套最常用的预设
            String[] classAnnotations = {
                "org.springframework.web.bind.annotation.RestController",
                "org.springframework.stereotype.Controller"
            };
            String[] methodAnnotations = {
                "org.springframework.web.bind.annotation.RequestMapping",
                "org.springframework.web.bind.annotation.GetMapping",
                "org.springframework.web.bind.annotation.PostMapping",
                "org.springframework.web.bind.annotation.PutMapping",
                "org.springframework.web.bind.annotation.DeleteMapping",
                "org.springframework.web.bind.annotation.PatchMapping"
            };
            classMatcher = ElementMatchers.none();
            methodMatcher = ElementMatchers.none();
            for (String ca : classAnnotations) {
                classMatcher = classMatcher.or(ElementMatchers.isAnnotatedWith(ElementMatchers.named(ca)));
            }
            for (String ma : methodAnnotations) {
                methodMatcher = methodMatcher.or(ElementMatchers.isAnnotatedWith(ElementMatchers.named(ma)));
            }
            System.out.println("[Monitor Agent] 使用内置默认策略（等待服务端下发）");
        } else {
            System.out.println("[Monitor Agent] 策略列表不为空");
            for (CaptureConfig cc : captures) {
                ElementMatcher.Junction<TypeDescription> cm = buildClassMatcher(cc);
                ElementMatcher.Junction<MethodDescription> mm = buildMethodMatcher(cc);

                classMatcher = (classMatcher == null) ? cm : classMatcher.or(cm);
                methodMatcher = (methodMatcher == null) ? mm : methodMatcher.or(mm);
            }
        }

        final ElementMatcher.Junction<TypeDescription> finalClassMatcher = classMatcher != null ? classMatcher : ElementMatchers.none();
        final ElementMatcher.Junction<MethodDescription> finalMethodMatcher = methodMatcher != null ? methodMatcher : ElementMatchers.none();

        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(ElementMatchers.nameStartsWith("com.monitor.agent"))
                .type(finalClassMatcher)
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module,
                                                            ProtectionDomain protectionDomain) {
                        return builder.method(finalMethodMatcher)
                                .intercept(Advice.to(ControllerInterceptor.class));
                    }
                })
                ;
    }

    /**
     * 根据单条策略构建类匹配器
     */
    private ElementMatcher.Junction<TypeDescription> buildClassMatcher(CaptureConfig cc) {
        String type = cc.getMatchType();
        String val = cc.getClassMatch();

        if (val == null || val.isEmpty()) return ElementMatchers.none();

        String matchType1 = type != null ? type : "annotation";
        switch (matchType1) {
            case "annotation":
                return ElementMatchers.isAnnotatedWith(buildAnnotationMatcher(val));
            case "class_name":
                return wildcardNameMatcher(val);
            case "package_name":
                return ElementMatchers.nameStartsWith(val.replace("%", ""));
            default:
                return ElementMatchers.none();
        }
    }

    /**
     * 根据单条策略构建方法匹配器
     */
    private ElementMatcher.Junction<MethodDescription> buildMethodMatcher(CaptureConfig cc) {
        String type = cc.getMatchType();
        String val = cc.getMethodMatch();

        // % 或 * 或空 = 拦截全部方法
        if (val == null || val.isEmpty() || "%".equals(val) || "*".equals(val)) {
            return ElementMatchers.any();
        }

        String matchType2 = type != null ? type : "annotation";
        switch (matchType2) {
            case "annotation":
                return ElementMatchers.isAnnotatedWith(buildAnnotationMatcher(val));
            case "class_name":
            case "package_name":
                return wildcardMethodMatcher(val);
            default:
                return ElementMatchers.any();
        }
    }

    /**
     * 构建注解匹配器 —— 支持通配符
     * isAnnotatedWith(ElementMatcher<? super TypeDescription>) 直接接受 TypeDescription 匹配器
     */
    private ElementMatcher.Junction<TypeDescription> buildAnnotationMatcher(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return ElementMatchers.none();
        }

        String normalized = pattern.replace("*", "%");

        if (normalized.endsWith("%") && normalized.length() > 1) {
            // xxx% 或 xxx.* → 前缀匹配（如 org.springframework.web.bind.annotation.%）
            String prefix = normalized.substring(0, normalized.length() - 1);
            if (prefix.endsWith(".")) prefix = prefix.substring(0, prefix.length() - 1);
            return ElementMatchers.nameStartsWith(prefix);
        } else if (normalized.startsWith("%") && normalized.length() > 1) {
            // %xxx → 后缀匹配（如 %Controller）
            return ElementMatchers.nameEndsWith(normalized.substring(1));
        } else if (normalized.contains("%")) {
            // x%y → 中间包含
            String[] parts = normalized.split("%", 2);
            String left = parts[0];
            String right = parts.length > 1 ? parts[1] : "";
            if (right.isEmpty() && !left.isEmpty()) return ElementMatchers.nameStartsWith(left);
            if (left.isEmpty() && !right.isEmpty()) return ElementMatchers.nameEndsWith(right);
            return ElementMatchers.nameContains(left.isEmpty() ? right : left);
        } else {
            // 精确匹配
            return ElementMatchers.named(normalized);
        }
    }

    /**
     * 类名通配符匹配：将 % 转为 ByteBuddy nameStartsWith/contains/nameEndsWith 组合
     */
    private ElementMatcher.Junction<TypeDescription> wildcardNameMatcher(String pattern) {
        String p = pattern.replace("*", "%");
        if (!p.contains("%")) {
            return ElementMatchers.named(p);
        }
        String prefix = p.substring(0, p.indexOf('%'));
        String suffix = p.substring(p.lastIndexOf('%') + 1);

        if (p.startsWith("%") && p.endsWith("%")) {
            // %xxx% → contains
            return ElementMatchers.nameContains(prefix.isEmpty() ? suffix : prefix);
        } else if (p.startsWith("%")) {
            // %xxx → endsWith
            return ElementMatchers.nameEndsWith(suffix);
        } else if (p.endsWith("%")) {
            // xxx% → startsWith
            return ElementMatchers.nameStartsWith(prefix);
        } else {
            return ElementMatchers.named(p.replace("%", ""));
        }
    }

    /**
     * 方法名通配符匹配
     */
    private ElementMatcher.Junction<MethodDescription> wildcardMethodMatcher(String pattern) {
        if (pattern == null || pattern.isEmpty() || "%".equals(pattern) || "*".equals(pattern)) {
            return ElementMatchers.any();
        }
        String p = pattern.replace("*", "%");
        if (!p.contains("%")) {
            return ElementMatchers.named(p);
        }
        String prefix = p.substring(0, p.indexOf('%'));
        String suffix = p.substring(p.lastIndexOf('%') + 1);

        if (p.startsWith("%") && p.endsWith("%")) {
            String mid = prefix.isEmpty() ? suffix : prefix;
            if (mid.isEmpty()) return ElementMatchers.any();
            return ElementMatchers.nameContains(mid);
        } else if (p.startsWith("%")) {
            return ElementMatchers.nameEndsWith(suffix);
        } else if (p.endsWith("%")) {
            return ElementMatchers.nameStartsWith(prefix);
        } else {
            return ElementMatchers.named(p.replace("%", ""));
        }
    }
}
