package com.taobao.arthas.core.command.klass100;

import java.lang.instrument.Instrumentation;

import org.apache.commons.lang.exception.ExceptionUtils;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.google.common.base.Joiner;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.command.express.Express;
import com.taobao.arthas.core.command.express.ExpressException;
import com.taobao.arthas.core.command.express.ExpressFactory;
import com.taobao.arthas.core.shell.cli.CliToken;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.Command;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.view.ObjectView;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

/**
 *
 * @author xhinliang
 *
 */
@Name("mvel")
@Summary("Execute mvel expression.")
@Description(Constants.EXAMPLE //
        + "  java.lang.System.out.println(\"hello\") \n" //
        + "  com.taobao.Singleton.getInstance() \n" //
        + "  com.taobao.Demo.staticFiled \n" //
        + "  def getBeanByName(name) { com.example.BeanFactory.getBean(name); } \n" //
        + "  xxxService.getInstance() // xxxService loaded by BeanFactory. \n" //
        + "" + Constants.WIKI + Constants.WIKI_HOME + "mvel\n" //
        + "  http://mvel.documentnode.com/" //
)
public class MvelCommand extends AnnotatedCommand {

    private static final Logger logger = LoggerFactory.getLogger(MvelCommand.class);

    private String express;

    private String hashCode;

    private int expand = 3;

    private static final Command INSTANCE = Command.create(MvelCommand.class);

    public static Command getInstance() {
        return INSTANCE;
    }

    @Argument(argName = "express", index = 0)
    @Description("The mvel expression.")
    public void setExpress(String express) {
        this.express = express;
    }

    @Option(shortName = "c", longName = "classLoader")
    @Description("The hash code of the special class's classLoader, default classLoader is SystemClassLoader.")
    public void setHashCode(String hashCode) {
        this.hashCode = hashCode;
    }

    @Option(shortName = "x", longName = "expand")
    @Description("Expand level of object (3 by default).")
    public void setExpand(Integer expand) {
        this.expand = expand;
    }

    @Override
    public void process(CommandProcess process) {
        int exitCode = 0;
        StringBuilder sb = new StringBuilder();
        for (CliToken cliToken: process.argsTokens()) {
            sb.append(cliToken.raw());
        }
        String evalString = sb.toString();
        ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Instrumentation inst = process.session().getInstrumentation();
            ClassLoader classLoader;
            if (hashCode == null) {
                classLoader = getDefaultClassLoader(inst);
            } else {
                classLoader = findClassLoader(inst, hashCode);
            }

            if (classLoader == null) {
                process.write("Can not find classloader with hashCode: " + hashCode + ".\n");
                exitCode = -1;
                return;
            }


            Thread.currentThread().setContextClassLoader(classLoader);
            Express unpooledExpress = ExpressFactory.mvelExpress(classLoader);
            try {
                Object value = unpooledExpress.get(evalString);
                String result = StringUtils.objectToString(expand >= 0 ? new ObjectView(value, expand).draw() : value);
                process.write(result + "\n");
            } catch (ExpressException e) {
                String rootMessage = exceptionToString(e);
                logger.warn("mvel: failed execute express: " + express, rootMessage);
                process.write("Failed to get static, exception message: " + e.getMessage()
                        + ", please check $HOME/logs/arthas/arthas.log for more details. \n");
                exitCode = -1;
            }
        } finally {
            Thread.currentThread().setContextClassLoader(oldClassLoader);
            process.end(exitCode);
        }
    }

    public static String exceptionToString(Exception e) {
        return Joiner.on("\n").join(ExceptionUtils.getRootCauseStackTrace(e));
    }

    private static ClassLoader findClassLoader(Instrumentation inst, String hashCode) {
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            ClassLoader classLoader = clazz.getClassLoader();
            if (classLoader != null && hashCode.equals(Integer.toHexString(classLoader.hashCode()))) {
                return classLoader;
            }
        }
        return null;
    }

    private static ClassLoader getDefaultClassLoader(Instrumentation inst) {
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            ClassLoader classLoader = clazz.getClassLoader();
            if (classLoader != null) {
                String classLoaderName = classLoader.getClass().getName();
                // 如果是 Tomcat 环境，优先使用 Tomcat 的 ClassLoader
                if (classLoaderName.equals("org.apache.catalina.loader.WebappClassLoader")) {
                    return classLoader;
                }
                if (classLoaderName.equals("org.apache.catalina.loader.ParallelWebappClassLoader")) {
                    return classLoader;
                }
                if (classLoaderName.equals("org.springframework.boot.loader.LaunchedURLClassLoader")) {
                    return classLoader;
                }
            }
        }
        return ClassLoader.getSystemClassLoader();
    }
}
