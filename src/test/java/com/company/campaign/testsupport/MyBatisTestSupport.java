package com.company.campaign.testsupport;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/** Shared test construction for the one business pool and its MyBatis-Plus session factory. */
public final class MyBatisTestSupport {

    private MyBatisTestSupport() {
    }

    public static SqlSessionFactory sessionFactory(DataSource dataSource) {
        try {
            MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(dataSource);
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/**/*.xml"));
            bean.setTypeAliasesPackage("com.company.campaign.persistence.entity");
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            bean.setConfiguration(configuration);
            return bean.getObject();
        } catch (Exception failure) {
            throw new IllegalStateException("The test MyBatis session factory could not be created", failure);
        }
    }

    public static HikariDataSource dataSource(
            String poolName, String url, String username, String password, String schema) {
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName(poolName);
        configuration.setDriverClassName("org.postgresql.Driver");
        configuration.setJdbcUrl(url);
        configuration.setUsername(username);
        configuration.setPassword(password);
        configuration.setMaximumPoolSize(4);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(5_000);
        configuration.setValidationTimeout(2_000);
        configuration.addDataSourceProperty("options",
                "-c search_path=" + schema
                        + " -c statement_timeout=10000"
                        + " -c lock_timeout=3000"
                        + " -c idle_in_transaction_session_timeout=15000");
        configuration.addDataSourceProperty("ApplicationName", poolName);
        return new HikariDataSource(configuration);
    }
}
