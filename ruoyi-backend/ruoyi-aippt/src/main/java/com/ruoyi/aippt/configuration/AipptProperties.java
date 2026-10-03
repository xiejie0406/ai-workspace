package com.ruoyi.aippt.configuration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component @ConfigurationProperties("aippt") public class AipptProperties  {
    private boolean enabled;
    private String environment="";
    private RedisDelivery redis=new RedisDelivery();
    private String catalogPath="classpath:aippt/catalog";
    private String storagePath="";
    private String compensationPath="";
    private String catalogVersion="aippt-1-90c52568ea7d";
    private String fontManifestHash="unavailable";
    private String storageDriver="local";
    private Cos cos=new Cos();
    private Worker worker=new Worker();
    private Provider provider=new Provider();
    private Provider image=new Provider();
    public Provider getImage()  {
        return image;
    }
    public void setImage(Provider value)  {
        image=value;
    }
    private Cleanup cleanup=new Cleanup();
    @jakarta.annotation.PostConstruct public void validateEnabledConfiguration()  {
        if(!enabled)return;
        if(redis.enabled)com.ruoyi.aippt.domain.AipptRules.require(environment!=null&&environment.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}"),"启用Redis派发须核定独立environment");
        com.ruoyi.aippt.domain.AipptRules.require(catalogPath!=null&&!catalogPath.isBlank(),"目录路径不能为空");
        if(worker.enabled)  {
            com.ruoyi.aippt.domain.AipptRules.require(worker.key!=null&&worker.key.length()>=32,"Worker机器钥匙至少32字符");
            com.ruoyi.aippt.domain.AipptRules.require(worker.leaseSeconds>=15&&worker.leaseSeconds<=300&&worker.maxAttempts>=1&&worker.maxAttempts<=5,"Worker租约配置超限");
            com.ruoyi.aippt.domain.AipptRules.require(worker.globalConcurrency>=1&&worker.globalConcurrency<=32&&worker.projectConcurrency>=1&&worker.projectConcurrency<=worker.globalConcurrency,"Worker并发配置无效");
        }
        com.ruoyi.aippt.domain.AipptRules.require(cleanup.orphanHours>=1&&cleanup.deletedDays>=1&&cleanup.exportDays>=1&&cleanup.eventDays>=1,"清理保留时间必须为正数");
    }
    public Cleanup getCleanup()  {
        return cleanup;
    }
    public void setCleanup(Cleanup value)  {
        cleanup=value;
    }
    public boolean isEnabled()  {
        return enabled;
    }
    public void setEnabled(boolean v)  {
        enabled=v;
    }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String value) { environment=value; }
    public RedisDelivery getRedis() { return redis; }
    public void setRedis(RedisDelivery value) { redis=value; }
    public static class RedisDelivery {
        private boolean enabled=true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled=value; }
    }
    public String getCatalogPath()  {
        return catalogPath;
    }
    public void setCatalogPath(String v)  {
        catalogPath=v;
    }
    public String getStoragePath()  {
        return storagePath;
    }
    public void setStoragePath(String v)  {
        storagePath=v;
    }
    public String getCompensationPath() { return compensationPath; }
    public void setCompensationPath(String v) { compensationPath=v; }
    public String getFontManifestHash()  {
        return fontManifestHash;
    }
    public void setFontManifestHash(String v)  {
        fontManifestHash=v;
    }
    public String getStorageDriver()  {
        return storageDriver;
    }
    public void setStorageDriver(String v)  {
        storageDriver=v;
    }
    public Cos getCos()  {
        return cos;
    }
    public void setCos(Cos v)  {
        cos=v;
    }
    public String getCatalogVersion()  {
        return catalogVersion;
    }
    public void setCatalogVersion(String v)  {
        catalogVersion=v;
    }
    public Worker getWorker()  {
        return worker;
    }
    public void setWorker(Worker v)  {
        worker=v;
    }
    public Provider getProvider()  {
        return provider;
    }
    public void setProvider(Provider v)  {
        provider=v;
    }
    public static class Worker  {
        private boolean enabled;
        private String key="";
        private int leaseSeconds=60,maxAttempts=2,globalConcurrency=6,projectConcurrency=3;
        public boolean isEnabled()  {
            return enabled;
        }
        public void setEnabled(boolean v)  {
            enabled=v;
        }
        public String getKey()  {
            return key;
        }
        public void setKey(String v)  {
            key=v;
        }
        public int getLeaseSeconds()  {
            return leaseSeconds;
        }
        public void setLeaseSeconds(int v)  {
            leaseSeconds=v;
        }
        public int getMaxAttempts()  {
            return maxAttempts;
        }
        public void setMaxAttempts(int v)  {
            maxAttempts=v;
        }
        public int getGlobalConcurrency()  {
            return globalConcurrency;
        }
        public void setGlobalConcurrency(int v)  {
            globalConcurrency=v;
        }
        public int getProjectConcurrency()  {
            return projectConcurrency;
        }
        public void setProjectConcurrency(int v)  {
            projectConcurrency=v;
        }
    }
    public static class Provider  {
        private boolean enabled;
        public boolean isEnabled()  {
            return enabled;
        }
        public void setEnabled(boolean v)  {
            enabled=v;
        }
    }
    public static class Cleanup  {
        private boolean enabled;
        private int orphanHours=24,deletedDays=7,exportDays=30,eventDays=7;
        public boolean isEnabled()  {
            return enabled;
        }
        public void setEnabled(boolean value)  {
            enabled=value;
        }
        public int getOrphanHours()  {
            return orphanHours;
        }
        public void setOrphanHours(int value)  {
            orphanHours=value;
        }
        public int getDeletedDays()  {
            return deletedDays;
        }
        public void setDeletedDays(int value)  {
            deletedDays=value;
        }
        public int getExportDays()  {
            return exportDays;
        }
        public void setExportDays(int value)  {
            exportDays=value;
        }
        public int getEventDays()  {
            return eventDays;
        }
        public void setEventDays(int value)  {
            eventDays=value;
        }
    }
    public static class Cos  {
        private String bucket="",region="",secretId="",secretKey="";
        public String getBucket()  {
            return bucket;
        }
        public void setBucket(String s)  {
            bucket=s;
        }
        public String getRegion()  {
            return region;
        }
        public void setRegion(String s)  {
            region=s;
        }
        public String getSecretId()  {
            return secretId;
        }
        public void setSecretId(String s)  {
            secretId=s;
        }
        public String getSecretKey()  {
            return secretKey;
        }
        public void setSecretKey(String s)  {
            secretKey=s;
        }
    }
}
