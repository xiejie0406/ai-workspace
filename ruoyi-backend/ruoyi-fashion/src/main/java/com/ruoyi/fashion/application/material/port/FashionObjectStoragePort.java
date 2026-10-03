package com.ruoyi.fashion.application.material.port;

import com.ruoyi.common.exception.ServiceException;
import java.time.Duration;

public interface FashionObjectStoragePort {
    StoredFashionObject putIfAbsent(String objectKey, byte[] content, String contentType);

    /**
     * 读取私有对象只供已经完成业务归属校验的服务使用。默认拒绝，保留测试替身的函数式接口兼容性。
     */
    default byte[] read(String objectKey) {
        throw new ServiceException("当前对象存储不支持读取");
    }

    /** 临时链接只由已验证素材归属及AI许可的业务/适配器调用；默认不开放私有文件。 */
    default String temporaryReadUrl(String objectKey, Duration validity) {
        throw new ServiceException("当前对象存储尚未配置试衣素材临时访问地址");
    }

    /** 试衣链接必须绑定已落库的任务，由读取端重查该任务实际SKU许可。 */
    default String temporaryReadUrl(String objectKey, Duration validity, String taskId) {
        throw new ServiceException("当前对象存储不支持绑定试衣任务的临时访问地址");
    }

    default boolean supportsTemporaryReadUrl() { return false; }
}
