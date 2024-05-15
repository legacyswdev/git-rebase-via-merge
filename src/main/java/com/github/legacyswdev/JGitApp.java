package com.github.legacyswdev;

import java.io.File;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

import jakarta.servlet.http.HttpServlet;

@SpringBootApplication
public class JGitApp {

	private static File repoBase;

	public static void main(String[] a) {
		if (a.length == 0 || !new File(a[0]).exists()) {
			throw new IllegalArgumentException("need a valid path as argument");
		}
		repoBase = new File(a[0]);
		SpringApplication.run(JGitApp.class, a);
	}

	@Bean
	public ServletRegistrationBean<HttpServlet> gitServlet() {
		ServletRegistrationBean<HttpServlet> servRegBean = new ServletRegistrationBean<>();
		servRegBean.setServlet(new EGitServletWrapper(repoBase));
		servRegBean.addUrlMappings("/egit/*");
		servRegBean.setLoadOnStartup(1);
		return servRegBean;
	}
}
