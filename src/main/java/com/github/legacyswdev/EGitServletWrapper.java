package com.github.legacyswdev;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.transport.resolver.FileResolver;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * This class is incomplete/hacky and should not be used in production. Translate
 * some ops from jakarta.servlet to javax.servlet
 */
public class EGitServletWrapper extends HttpServlet {

	private final GitServlet targetServlet;
	private final File baseRepoPath;
	private static final ThreadLocal<ServletOutputStream> SERVLET_THREAD_OUTPUT_STREAM = new ThreadLocal<>();
	private static final ThreadLocal<ServletInputStream> SERVLET_THREAD_INPUT_STREAM = new ThreadLocal<>();

	public EGitServletWrapper(File baseRepoPath) {
		this.targetServlet = new GitServlet();
		this.baseRepoPath = baseRepoPath;
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		RepositoryResolver<javax.servlet.http.HttpServletRequest> resolver = new FileResolver<>(baseRepoPath, true);
		targetServlet.setRepositoryResolver(resolver);
		javax.servlet.ServletConfig javaxConfig = createProxy(javax.servlet.ServletConfig.class, config,
				getServletContext());
		try {
			targetServlet.init(javaxConfig);
		} catch (javax.servlet.ServletException e) {
			throw new ServletException(e);
		}
	}

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		SERVLET_THREAD_INPUT_STREAM.set(req.getInputStream());
		SERVLET_THREAD_OUTPUT_STREAM.set(resp.getOutputStream());
		javax.servlet.http.HttpServletRequest javaxRequest = createProxy(javax.servlet.http.HttpServletRequest.class,
				req, getServletContext());
		javax.servlet.http.HttpServletResponse javaxResponse = createProxy(javax.servlet.http.HttpServletResponse.class,
				resp, getServletContext());
		try {
			targetServlet.service(javaxRequest, javaxResponse);
		} catch (javax.servlet.ServletException e) {
			throw new ServletException(e);
		}
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		super.doGet(req, resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		super.doPost(req, resp);
	}

	private static <T> T createProxy(Class<T> javaxClass, Object jakartaTarget, ServletContext context) {
		return (T) Proxy.newProxyInstance(EGitServletWrapper.class.getClassLoader(), new Class[] { javaxClass },
				new WrapperInvocationHandler(context, jakartaTarget));
	}

	private static class WrapperInvocationHandler implements InvocationHandler {
		private final ServletContext context;
		private final Object target;

		public WrapperInvocationHandler(ServletContext context, Object target) {
			this.target = target;
			this.context = context;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (target == null) {
				return target;
			}
			if (target instanceof WrapperInvocationHandler) {
				if ("log".equals(method.getName())
						&& javax.servlet.ServletContext.class.equals(method.getDeclaringClass())) {
					if (args.length == 1) {
						this.context.log((String) args[0]);
					} else if (args.length == 2) {
						((Throwable) args[1]).printStackTrace();
						this.context.log((String) args[0], (Throwable) args[1]);
					}
					return null;
				}
			}
			Method targetMethod = target.getClass().getDeclaredMethod(method.getName(), method.getParameterTypes());
			targetMethod.setAccessible(true);
			if (args != null) {
				Class[] targetParameterTypes = targetMethod.getParameterTypes();
				for (int i = 0; i < targetParameterTypes.length; i++) {
					if (!args[i].getClass().equals(targetParameterTypes[i])
							&& !targetParameterTypes[i].isAssignableFrom(args[i].getClass())) {
						if (!args[i].getClass().isPrimitive() && !targetParameterTypes[i].isPrimitive()) {
							if (targetParameterTypes[i].isInterface()) {
								args[i] = createProxy(targetParameterTypes[i],
										new WrapperInvocationHandler(context, args[i]), context);
							} else {
								throw new IllegalArgumentException("WrapperInvocationHandler TODO ---------> " + method);
							}
						}
					}
				}
			}
			Object returnObject = targetMethod.invoke(target, args);
			if (method.getReturnType() != null && !method.getReturnType().equals(targetMethod.getReturnType())
					&& !targetMethod.getReturnType().isAssignableFrom(method.getReturnType())) {
				if (!method.getReturnType().isPrimitive() && !targetMethod.getReturnType().isPrimitive()) {
					if (method.getReturnType().isInterface()) {
						return createProxy(method.getReturnType(), new WrapperInvocationHandler(context, returnObject),
								context);
					} else if (javax.servlet.ServletOutputStream.class.equals(method.getReturnType())) {
						return new javax.servlet.ServletOutputStream() {
							@Override
							public void write(int arg0) throws IOException {
								SERVLET_THREAD_OUTPUT_STREAM.get().write(arg0);
							}

							@Override
							public void setWriteListener(javax.servlet.WriteListener writeListener) {
								SERVLET_THREAD_OUTPUT_STREAM.get().setWriteListener(new WriteListener() {
									@Override
									public void onWritePossible() throws IOException {
										writeListener.onWritePossible();
									}

									@Override
									public void onError(Throwable throwable) {
										writeListener.onError(throwable);
									}
								});
							}

							@Override
							public boolean isReady() {
								return SERVLET_THREAD_OUTPUT_STREAM.get().isReady();
							}
						};
					} else if (javax.servlet.ServletInputStream.class.equals(method.getReturnType())) {
						return new javax.servlet.ServletInputStream() {

							@Override
							public boolean isFinished() {
								return SERVLET_THREAD_INPUT_STREAM.get().isFinished();
							}

							@Override
							public boolean isReady() {
								return SERVLET_THREAD_INPUT_STREAM.get().isReady();
							}

							@Override
							public void setReadListener(javax.servlet.ReadListener readListener) {
								SERVLET_THREAD_INPUT_STREAM.get().setReadListener(new ReadListener() {
									
									@Override
									public void onError(Throwable throwable) {
										readListener.onError(throwable);
									}
									
									@Override
									public void onDataAvailable() throws IOException {
										readListener.onDataAvailable();
									}
									
									@Override
									public void onAllDataRead() throws IOException {
										readListener.onAllDataRead();
									}
								});
							}

							@Override
							public int read() throws IOException {
								return SERVLET_THREAD_INPUT_STREAM.get().read();
							}};
					} else {
						throw new IllegalArgumentException("WrapperInvocationHandler TODO ---------> " + method);
					}
				}
			}
			return returnObject;
		}

	}

}
