package com.github.legacyswdev;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;



import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.transport.resolver.FileResolver;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * This class is incomplete/hacky and should not be used in production
 * Translate some ops from jakarta.servlet to javax.servlet
 */
public class EGitServletWrapper extends HttpServlet {

	private final GitServlet targetServlet;
	private final File baseRepoPath;
	private static final ThreadLocal<ServletOutputStream> SERVLET_THREAD_OUTPUT_STREAM = new ThreadLocal<>();
	
	
	public EGitServletWrapper(File baseRepoPath) {
		this.targetServlet = new GitServlet();
		this.baseRepoPath = baseRepoPath;
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		RepositoryResolver<javax.servlet.http.HttpServletRequest> resolver = new FileResolver<>(baseRepoPath, true);
		targetServlet.setRepositoryResolver(resolver);
		javax.servlet.ServletConfig javaxConfig = createProxy(javax.servlet.ServletConfig.class, config);
		try {
			targetServlet.init(javaxConfig);
		} catch (javax.servlet.ServletException e) {
			throw new ServletException(e);
		}
		
	}

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		super.service(req, resp);
		SERVLET_THREAD_OUTPUT_STREAM.set(resp.getOutputStream());
		javax.servlet.http.HttpServletRequest javaxRequest = createProxy(javax.servlet.http.HttpServletRequest.class, req);
		javax.servlet.http.HttpServletResponse javaxResponse = createProxy(javax.servlet.http.HttpServletResponse.class,resp);
		try {
			targetServlet.service(javaxRequest, javaxResponse);
		} catch (javax.servlet.ServletException e) {
			throw new ServletException(e);
		} 
	}
	
	
	private static <T> T createProxy(Class<T> javaxClass,Object jakartaTarget) {
		return (T)Proxy.newProxyInstance(EGitServletWrapper.class.getClassLoader(), new Class[] {javaxClass}, new WrapperInvocationHandler(jakartaTarget));
	}
	
	private static class WrapperInvocationHandler implements InvocationHandler {
		private final Object target;

		public WrapperInvocationHandler(Object target) {
			this.target = target;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if(target==null) {
				return target;
			}
			Method targetMethod = target.getClass().getDeclaredMethod(method.getName(), method.getParameterTypes());
			targetMethod.setAccessible(true);
			if(args!=null) {
				Class[] targetParameterTypes = targetMethod.getParameterTypes();				
				for(int i=0;i<targetParameterTypes.length;i++) {
					if(!args[i].getClass().equals(targetParameterTypes[i])
							&&   !targetParameterTypes[i].isAssignableFrom(args[i].getClass())) {
						if(!args[i].getClass().isPrimitive()&&!targetParameterTypes[i].isPrimitive()) {
							args[i] = createProxy(targetParameterTypes[i],new WrapperInvocationHandler(args[i]));
						} 						
					}
				}					
			}
			Object returnObject = targetMethod.invoke(target,args);
			if(method.getReturnType()!=null && !method.getReturnType().equals(targetMethod.getReturnType())
					&& !targetMethod.getReturnType().isAssignableFrom(method.getReturnType())) {
				if(!method.getReturnType().isPrimitive()&&!targetMethod.getReturnType().isPrimitive()) {
					if(method.getReturnType().isInterface()) {
						return createProxy(method.getReturnType(),new WrapperInvocationHandler(returnObject));
					} else if(javax.servlet.ServletOutputStream.class.equals(method.getReturnType())) {
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
					} else {
						throw new IllegalArgumentException("TODO "+method.getName()+" "+method.getReturnType());
					}
				}
			}
			return returnObject;
		}
		
	}

}
