/*
 * ====================================================================
 * 龙果学院： www.roncoo.com （微信公众号：RonCoo_com）
 * 超级教程系列：《微服务架构的分布式事务解决方案》视频教程
 * 讲师：吴水成（水到渠成），840765167@qq.com
 * 课程地址：http://www.roncoo.com/course/view/7ae3d7eddc4742f78b0548aa8bd9ccdb
 * ====================================================================
 */
package com.roncoo.pay.app.notify.core;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.alibaba.fastjson.JSONObject;
import com.roncoo.pay.app.notify.App;
import com.roncoo.pay.app.notify.entity.NotifyParam;
import com.roncoo.pay.common.core.exception.BizException;
import com.roncoo.pay.common.core.utils.httpclient.SimpleHttpParam;
import com.roncoo.pay.common.core.utils.httpclient.SimpleHttpResult;
import com.roncoo.pay.common.core.utils.httpclient.SimpleHttpUtils;
import com.roncoo.pay.service.notify.entity.RpNotifyRecord;
import com.roncoo.pay.service.notify.enums.NotifyStatusEnum;

/**
 * @功能说明:
 * @创建者: Peter
 * @创建时间: 16/6/2  下午5:34
 * @公司名称:广州市领课网络科技有限公司 龙果学院(www.roncoo.com)
 * @版本:V1.0
 */
public class NotifyTask implements Runnable, Delayed {

    private static final Log LOG = LogFactory.getLog(NotifyTask.class);

    private long executeTime;

    private RpNotifyRecord notifyRecord;

    private NotifyQueue notifyQueue;

    private NotifyParam notifyParam;

    private NotifyPersist notifyPersist = App.notifyPersist;

    public NotifyTask() {
    }

    public NotifyTask(RpNotifyRecord notifyRecord, NotifyQueue notifyQueue, NotifyParam notifyParam) {
        super();
        this.notifyRecord = notifyRecord;
        this.notifyQueue = notifyQueue;
        this.notifyParam = notifyParam;
        this.executeTime = getExecuteTime(notifyRecord);
    }

    private long getExecuteTime(RpNotifyRecord record) {
        long lastTime = record.getLastNotifyTime().getTime();
        Integer nextNotifyTime = notifyParam.getNotifyParams().get(record.getNotifyTimes());
        return (nextNotifyTime == null ? 0 : nextNotifyTime * 1000) + lastTime;
    }

    public int compareTo(Delayed o) {
        NotifyTask task = (NotifyTask) o;
        return executeTime > task.executeTime ? 1 : (executeTime < task.executeTime ? -1 : 0);
    }

    public long getDelay(TimeUnit unit) {
        return unit.convert(executeTime - System.currentTimeMillis(), unit.SECONDS);
    }

    public void run() {
        // 得到当前通知对象的通知次数
        Integer notifyTimes = notifyRecord.getNotifyTimes();
        // 去通知
        try {
            LOG.info("Notify Url " + notifyRecord.getUrl()+" ;notify id:"+notifyRecord.getId()+";notify times:"+notifyRecord.getNotifyTimes());

            /** 采用 httpClient */
            SimpleHttpParam param = new SimpleHttpParam(notifyRecord.getUrl());
            SimpleHttpResult result = SimpleHttpUtils.httpRequest(param);

            notifyRecord.setNotifyTimes(notifyTimes + 1);
            String successValue = notifyParam.getSuccessValue();

            String responseMsg = "";
            Integer responseStatus = result.getStatusCode();

            // 得到返回状态，如果是200，也就是通知成功
            if (result != null
                    && (responseStatus == 200 || responseStatus == 201 || responseStatus == 202 || responseStatus == 203
                    || responseStatus == 204 || responseStatus == 205 || responseStatus == 206)) {
                responseMsg = result.getContent().trim();
                responseMsg = responseMsg.length() >= 600 ? responseMsg.substring(0, 600) : responseMsg;
                LOG.info("订单号： " + notifyRecord.getMerchantOrderNo() + " HTTP_STATUS：" + responseStatus + "请求返回信息：" + responseMsg);
                // 通知成功
                if (responseMsg.trim().equals(successValue)) {
                    notifyPersist.updateNotifyRord(notifyRecord.getId(), notifyRecord.getNotifyTimes(), NotifyStatusEnum.SUCCESS.name());
                } else {
                    notifyQueue.addElementToList(notifyRecord);
                    notifyPersist.updateNotifyRord(notifyRecord.getId(), notifyRecord.getNotifyTimes(),
                            NotifyStatusEnum.HTTP_REQUEST_SUCCESS.name());

                }
                LOG.info("Update NotifyRecord:" + JSONObject.toJSONString(notifyRecord)+";responseMsg:"+responseMsg);
            } else {
                notifyQueue.addElementToList(notifyRecord);
                // 再次放到通知列表中，由添加程序判断是否已经通知完毕或者通知失败
                notifyPersist.updateNotifyRord(notifyRecord.getId(), notifyRecord.getNotifyTimes(),
                        NotifyStatusEnum.HTTP_REQUEST_FALIED.name());
            }

            // 写通知日志表
            notifyPersist.saveNotifyRecordLogs(notifyRecord.getId(), notifyRecord.getMerchantNo(), notifyRecord.getMerchantOrderNo(),
                    notifyRecord.getUrl(), responseMsg, responseStatus);
            LOG.info("Insert NotifyRecordLog, merchantNo:" + notifyRecord.getMerchantNo() + ",merchantOrderNo:"
                    + notifyRecord.getMerchantOrderNo());
        } catch (BizException e) {
            LOG.error("NotifyTask", e);
        } catch (Exception e) {
            LOG.error("NotifyTask", e);
            notifyQueue.addElementToList(notifyRecord);

            notifyPersist.updateNotifyRord(notifyRecord.getId(), notifyRecord.getNotifyTimes(),
                    NotifyStatusEnum.HTTP_REQUEST_FALIED.name());
            notifyPersist.saveNotifyRecordLogs(notifyRecord.getId(), notifyRecord.getMerchantNo(), notifyRecord.getMerchantOrderNo(),
                    notifyRecord.getUrl(), "", 0);
        }

    }

}
