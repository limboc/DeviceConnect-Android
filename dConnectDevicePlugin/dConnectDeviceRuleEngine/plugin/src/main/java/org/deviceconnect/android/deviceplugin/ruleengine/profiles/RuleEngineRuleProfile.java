/*
 RuleEngineRuleProfile.java
 Copyright (c) 2018 NTT DOCOMO,INC.
 Released under the MIT license
 http://opensource.org/licenses/mit-license.php
 */
package org.deviceconnect.android.deviceplugin.ruleengine.profiles;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.deviceconnect.android.deviceplugin.ruleengine.RuleEngineApplication;
import org.deviceconnect.android.deviceplugin.ruleengine.RuleEngineMessageService;
import org.deviceconnect.android.deviceplugin.ruleengine.params.Actions;
import org.deviceconnect.android.deviceplugin.ruleengine.params.AndRule;
import org.deviceconnect.android.deviceplugin.ruleengine.params.ComparisonValue;
import org.deviceconnect.android.deviceplugin.ruleengine.params.ErrorStatus;
import org.deviceconnect.android.deviceplugin.ruleengine.params.Operation;
import org.deviceconnect.android.deviceplugin.ruleengine.params.Rule;
import org.deviceconnect.android.deviceplugin.ruleengine.params.RuleType;
import org.deviceconnect.android.deviceplugin.ruleengine.params.SettingData;
import org.deviceconnect.android.deviceplugin.ruleengine.params.Trigger;
import org.deviceconnect.android.deviceplugin.ruleengine.utils.ComparisonUtil;
import org.deviceconnect.android.deviceplugin.ruleengine.utils.DConnectHelper;
import org.deviceconnect.android.deviceplugin.ruleengine.utils.TimeUnitUtil;
import org.deviceconnect.android.deviceplugin.ruleengine.utils.TimerUtil;
import org.deviceconnect.android.deviceplugin.ruleengine.utils.Utils;
import org.deviceconnect.android.event.Event;
import org.deviceconnect.android.event.EventError;
import org.deviceconnect.android.event.EventManager;
import org.deviceconnect.android.message.MessageUtils;
import org.deviceconnect.android.profile.DConnectProfile;
import org.deviceconnect.android.profile.api.DeleteApi;
import org.deviceconnect.android.profile.api.GetApi;
import org.deviceconnect.android.profile.api.PostApi;
import org.deviceconnect.android.profile.api.PutApi;
import org.deviceconnect.message.DConnectEventMessage;
import org.deviceconnect.message.DConnectMessage;
import org.deviceconnect.message.DConnectResponseMessage;
import org.deviceconnect.message.DConnectSDK;
import org.deviceconnect.message.DConnectSDKFactory;
import org.deviceconnect.utils.RFC3339DateUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.deviceconnect.utils.RFC3339DateUtils.nowTimeStampString;

/**
 * RuleProfileクラス.
 * @author NTT DOCOMO, INC.
 */
public class RuleEngineRuleProfile extends DConnectProfile {
    /** プロファイル名. */
    public static final String PROFILE_NAME = "rule";
    /** Date&Time識別子. */
    public static final String DATE_TIME = "Date&Time";
    /** 処理遅延発生時の次回処理設定 : SKIP識別子. */
    public static final String SKIP = "skip";
    /** 処理遅延発生時の次回処理設定 : STACK識別子. */
    public static final String STACK = "stack";

    /** 比較値選択 : 左辺. */
    private static final String SELECT_LEFT = "left";
    /** 比較値選択 : 右辺. */
    private static final String SELECT_RIGHT = "right";
    /** ルール構造体. */
    private Rule mRule;
    /** AND条件成立格納配列. */
    private List<AndEstablishment> mAndEstablishmentList = new ArrayList<>();
    /** rest から profileを切り出す際の指標. */
    private static String GOTAPI_PHRASE = "/gotapi/";
    /** ServiceDiscovery 取得データ. */
    private List<DConnectHelper.ServiceInfo> mServiceInfos;
    /** 開始待ちフラグ. */
    private boolean mManagerStart = false;
    /** Device Connect SDK. */
    private DConnectSDK mSDK;


    private final DConnectSDK.OnEventListener mAndEventListener = new DConnectSDK.OnEventListener() {
        @Override
        public void onMessage(final  DConnectEventMessage message) {
            DConnectMessage coomparisonResult = message.getMessage("coomparisonResult");
            String ruleServiceId = coomparisonResult.getString("ruleServiceId");
            String timestamp = coomparisonResult.getString("timestamp");
            if (ruleServiceId != null && timestamp != null) {
                andProcess(ruleServiceId, timestamp);
            }
        }

        @Override
        public void onResponse(final DConnectResponseMessage message) {

        }
    };

    /**
     * Constructor.
     * @param type ルールサービスタイプ.
     */
    public RuleEngineRuleProfile(final String type) {
        // ルール構造体生成.
        mRule = new Rule(type);
        Context context = RuleEngineApplication.getInstance();
        mSDK = DConnectSDKFactory.create(context, DConnectSDKFactory.Type.HTTP);
        mSDK.setOrigin(context.getPackageName());

        // POST /rule/andRule
        addApi(new PostApi() {
            @Override
            public String getAttribute() {
                return "andRule";
            }

            @Override
            public boolean onRequest(final Intent request, final Intent response) {
                String serviceId = (String) request.getExtras().get("serviceId");
                String andRuleServiceId = (String) request.getExtras().get("andRuleServiceId");

                if (!mRule.getRuleServiceType().contains(RuleType.AND)) {
                    MessageUtils.setIllegalDeviceStateError(response, "Illegal rule type.");
                    return true;
                }

                // AND判定用RuleServiceID設定処理.
                // データ数チェック.
                StringTokenizer countSt = new StringTokenizer(andRuleServiceId,",");
                int count = countSt.countTokens();
                if (count == 0) {
                    MessageUtils.setInvalidRequestParameterError(response, "andRuleServiceId is illegal.");
                    return true;
                }
                // AND条件成立格納配列初期化.
                if (mAndEstablishmentList.size() != 0) {
                    mAndEstablishmentList.clear();
                }
                // トークン切り出し.
                List<String> andRuleServiceIds = new ArrayList<>();
                StringTokenizer st = new StringTokenizer(andRuleServiceId,",");
                int index = 0;
                while (st.hasMoreTokens()) {
                    String id = st.nextToken();
                    andRuleServiceIds.add(id);
                    AndEstablishment ae = new AndEstablishment();
                    ae.setRuleServiceId(id);
                    mAndEstablishmentList.add(ae);
                }
                // AND判定用RuleServiceID設定.
                mRule.getAndRule().setAndRuleServiceId(andRuleServiceIds);

                // 判定時間設定処理.
                Float judgementTime = parseFloat(request, "judgementTime");
                long judgementTimeValue = judgementTime.longValue();
                if (judgementTimeValue < 0) {
                    MessageUtils.setInvalidRequestParameterError(response, "judgementTime is illegal.");
                    return true;
                }
                // 判定時間設定.
                mRule.getAndRule().setJudgementTime(judgementTimeValue);

                // 判定時間単位設定処理.
                String judgementTimeUnit = (String) request.getExtras().get("judgementTimeUnit");
                if (judgementTimeUnit == null) {
                    setResult(response, DConnectMessage.RESULT_OK);
                    ((RuleEngineMessageService) getContext()).updateRuleData(mRule);
                } else if (TimeUnitUtil.checkTimeUnit(judgementTimeUnit)) {
                    // 判定時間単位設定.
                    mRule.getAndRule().setJudgementTimeUnit(judgementTimeUnit);
                    setResult(response, DConnectMessage.RESULT_OK);
                    ((RuleEngineMessageService) getContext()).updateRuleData(mRule);
                } else {
                    MessageUtils.setInvalidRequestParameterError(response, "judgementTimeUnit is illegal.");
                }
                return true;
            }
        });

        // PUT /rule/onOperation
        addApi(new PutApi() {
            @Override
            public String getAttribute() {
                return "onOperation";
            }

            @Override
            public boolean onRequest(final Intent request, final Intent response) {
                final String serviceId = (String) request.getExtras().get("serviceId");

                // ルール状態設定.
                mRule.setRuleEnable(true);
                // エラーステータス初期化.
                setErrorStatus(ErrorStatus.STATUS_NORMAL);
                ((RuleEngineMessageService) getContext()).updateRuleData(mRule);

                // マネージャー接続.
                connectDCM();

                // ANDルール確認.
                if (mRule.getRuleServiceType().contains(RuleType.AND)) {
                    // AND ルール取得.
                    AndRule andRule = mRule.getAndRule();
                    // AND ruleServiceID取得.
                    List<String> andRuleList = andRule.getAndRuleServiceId();
                    if (andRuleList.size() == 0) {
                        // AND条件未設定.
                        MessageUtils.setInvalidRequestParameterError(response, "AND Rule not set.");
                        return true;
                    }

                    for (final String ruleServiceId : andRuleList) {
                        final String rest = "/gotapi/rule/onOperation";
                        executor.submit(new Runnable() {
                            @Override
                            public void run() {
                                Utils.sendRequest(getContext(), "PUT", rest, ruleServiceId, null, new DConnectHelper.FinishCallback<Map<String, Object>>() {
                                    @Override
                                    public void onFinish(Map<String, Object> stringObjectMap, Exception error) {
                                        if (error != null) {
                                            // エラー通知.
                                            String errorStatus = "AND Rule execute error. errorCode = " + stringObjectMap.get("errorCode") + " (" + stringObjectMap.get("errorMessage") + ")";
                                            setErrorStatus(errorStatus);
                                            // エラー情報通知
                                            ((RuleEngineMessageService) getContext()).sendEventErrorStatus(ruleServiceId, mRule.getErrorStarus(), mRule.getErrorTimestamp());
                                        } else {
                                            // WebSocket設定.
                                            connectWebSocket();
                                            // イベント受信設定.
                                            DConnectSDK.URIBuilder uriBuilder = mSDK.createURIBuilder();
                                            uriBuilder.setServiceId(ruleServiceId);
                                            uriBuilder.setProfile(PROFILE_NAME);
                                            uriBuilder.setAttribute("onOperation");
                                            mSDK.addEventListener(uriBuilder.build(), mAndEventListener);
                                        }
                                    }
                                });
                            }
                        });
                    }

                } else {
                    // インターバルタイマー起動処理.
                    TimerTask task = new TimerTask() {
                        @Override
                        public void run() {
                            Trigger trigger = mRule.getTrigger();
                            String actionType = trigger.getAction();
                            String rest = trigger.getRest();
                            String parameter = trigger.getParameter();
                            if (!rest.contains(DATE_TIME)) {
                                // DateTime以外はREST実行
                                execRest(request, actionType, rest, parameter);
                            } else {
                                executeProcess(request, null);
                            }
                        }
                    };
                    long interval = TimeUnitUtil.changeMSec(mRule.getTrigger().getInterval(), mRule.getTrigger().getIntervalUnit());
                    String refernceTime = mRule.getTrigger().getReferenceTime();
                    String delayOccurrence = Operation.SKIP;
                    List<Operation> operations = mRule.getOperations();
                    for (Operation operation : operations) {
                        if (operation.getDelayOccurrence().contains(Operation.STACK)) {
                            delayOccurrence = Operation.STACK;
                            break;
                        }
                    }
                    startTimer(serviceId, task, interval, refernceTime, delayOccurrence);
                    // ルール状態設定.
                    mRule.setRuleEnable(true);
                    ((RuleEngineMessageService) getContext()).updateRuleData(mRule);

                    // イベント登録.
                    EventError error = EventManager.INSTANCE.addEvent(request);
                    switch (error) {
                        case NONE:
                            setResult(response, DConnectMessage.RESULT_OK);
                            break;
                        case INVALID_PARAMETER:
                            MessageUtils.setInvalidRequestParameterError(response);
                            break;
                        default:
                            MessageUtils.setUnknownError(response);
                            break;
                    }
                }

                return true;
            }
        });

        // DELETE /rule/onOperation
        addApi(new DeleteApi() {
            @Override
            public String getAttribute() {
                return "onOperation";
            }

            @Override
            public boolean onRequest(final Intent request, final Intent response) {
                String serviceId = (String) request.getExtras().get("serviceId");

                // ルール状態設定.
                mRule.setRuleEnable(false);
                ((RuleEngineMessageService) getContext()).updateRuleData(mRule);

                // インターバルタイマー停止処理.
                stopTimer(serviceId);

                // イベント解除.
                EventError error = EventManager.INSTANCE.removeEvent(request);
                switch (error) {
                    case NONE:
                        setResult(response, DConnectMessage.RESULT_OK);
                        break;
                    case INVALID_PARAMETER:
                        MessageUtils.setInvalidRequestParameterError(response);
                        break;
                    case NOT_FOUND:
                        MessageUtils.setUnknownError(response, "Event is not registered.");
                        break;
                    default:
                        MessageUtils.setUnknownError(response);
                        break;
                }
                return true;
            }
        });

        // GET /rule/onOperation
        addApi(new GetApi() {
            @Override
            public String getAttribute() {
                return "onOperation";
            }

            @Override
            public boolean onRequest(final Intent request, final Intent response) {
                String serviceId = (String) request.getExtras().get("serviceId");

                // 応答処理.
                setResult(response, DConnectMessage.RESULT_OK);
                Bundle root = response.getExtras();
                root.putBoolean("ruleEnable", mRule.isRuleEnable());
                response.putExtras(root);
                return true;
            }
        });

        // POST /rule/operation
        addApi(new PostApi() {
            @Override
            public String getAttribute() {
                return "operation";
            }

            @Override
            public boolean onRequest(final Intent request, final Intent response) {
                String serviceId = (String) request.getExtras().get("serviceId");

                // オペレーション設定処理.
                Operation operation = new Operation();
                // REST設定処理.
                String operationRest = (String) request.getExtras().get("operation");
                if (operationRest != null) {
                    operation.setRest(operationRest);
                }
                // Action設定処理.
                String operationAction = (String) request.getExtras().get("operationAction");
                if (operationAction != null) {
                    switch(operationAction) {
                        case Actions.DELETE:
                        case Actions.GET:
                        case Actions.POST:
                        case Actions.PUT:
                            operation.setAction(operationAction);
                            break;
                        default:
                            break;
                    }
                }
                // Parameter設定処理.
                String operationParameter = (String) request.getExtras().get("operationParameter");
                if (operationParameter != null) {
                    operation.setParameter(operationParameter);
                }
                // 処理遅延発生時の次回処理設定処理.
                String delayOccurrence = (String) request.getExtras().get("delayOccurrence");
                if (delayOccurrence != null) {
                    switch (delayOccurrence.toLowerCase()) {
                        case SKIP:
                        case STACK:
                            operation.setDelayOccurrence(delayOccurrence);
                            break;
                        default:
                            MessageUtils.setInvalidRequestParameterError(response, "delayOccurrence is illegal.");
                            return true;
                    }
                }

                if (operationRest == null && operationAction == null && operationParameter == null && delayOccurrence == null) {
                    MessageUtils.setInvalidRequestParameterError(response, "All parameters are not set.");
                    return true;
                }

                // インデックス設定処理.
                Float operationIndex = parseFloat(request, "operationIndex");
                long index;
                if (operationIndex != null) {
                    index = operationIndex.longValue();
                    operation.setIndex(index);
                    if (mRule.setOperation(operation) == -1) {
                        MessageUtils.setInvalidRequestParameterError(response, "operationIndex is illegal.");
                        return true;
                    }
                } else {
                    index = mRule.addOperation(operation);
                }
                ((RuleEngineMessageService) getContext()).updateRuleData(mRule);

                // 応答作成.
                setResult(response, DConnectMessage.RESULT_OK);
                Bundle root = response.getExtras();
                root.putFloat("operationIndex", index);
                response.putExtras(root);
                return true;
            }
        });

        // DELETE /rule/operation
        addApi(new DeleteApi() {
            @Override
            public String getAttribute() {
                return "operation";
            }

            @Override
            public boolean onRequest(final Intent request, final Intent response) {
                String serviceId = (String) request.getExtras().get("serviceId");

                // 指定オペレーション削除.
                Float operationIndex = parseFloat(request, "operationIndex");
                if (operationIndex == null) {
                    MessageUtils.setInvalidRequestParameterError(response, "operationIndex is illegal.");
                } else {
                    if (mRule.deleteOperation(operationIndex.longValue())) {
                        setResult(response, DConnectMessage.RESULT_OK);
                        ((RuleEngineMessageService) getContext()).updateRuleData(mRule);
                    } else {
                        MessageUtils.setInvalidRequestParameterError(response, "operationIndex is illegal.");
                    }
                }
                return true;
            }
        });

        // POST /rule/trigger
        addApi(new PostApi() {
            @Override
            public String getAttribute() {
                return "trigger";
            }

            @Override
            public boolean onRequest(final Intent request, final Intent response) {
                String serviceId = (String) request.getExtras().get("serviceId");

                // トリガー構造体取得.
                Trigger trigger = mRule.getTrigger();
                if (trigger == null) {
                    trigger = new Trigger();
                }

                // トリガーインターバル・単位設定処理.
                if (!setInterval(request, response, trigger)) {
                    return true;
                }

                // トリガーRest設定処理.
                String triggerRest = (String) request.getExtras().get("trigger");
                if (triggerRest != null) {
                    trigger.setRest(triggerRest);
                } else {
                    MessageUtils.setInvalidRequestParameterError(response, "trigger is illegal.");
                    return true;
                }

                // トリガーAction設定処理.
                String triggerAction = (String) request.getExtras().get("triggerAction");
                if (triggerAction != null) {
                    switch(triggerAction.toUpperCase()) {
                        case Actions.DELETE:
                        case Actions.GET:
                        case Actions.POST:
                        case Actions.PUT:
                            trigger.setAction(triggerAction);
                            break;
                        default:
                            break;
                    }
                } else {
                    if (!triggerRest.contains(DATE_TIME)) {
                        MessageUtils.setInvalidRequestParameterError(response, "triggerAction is illegal.");
                        return true;
                    }
                }

                // トリガーパラメータ設定処理.
                String triggerParameter = (String) request.getExtras().get("triggerParameter");
                if (triggerParameter != null) {
                    trigger.setParameter(triggerParameter);
                } else {
                    if (!triggerRest.contains(DATE_TIME)) {
                        MessageUtils.setInvalidRequestParameterError(response, "triggerParameter is illegal.");
                        return true;
                    }
                }

                // 比較値(左辺)設定処理.
                String comparisonLeft = (String) request.getExtras().get("comparisonLeft");
                if (comparisonLeft != null) {
                    trigger.setComparisionLeft(comparisonLeft);
                }

                // 比較値(左辺)データタイプ設定処理.
                String comparisonLeftDataType = (String) request.getExtras().get("comparisonLeftDataType");
                if (comparisonLeftDataType != null) {
                    if (ComparisonUtil.checkDataType(comparisonLeftDataType)) {
                        trigger.setComparisionLeftDataType(comparisonLeftDataType);
                    } else {
                        MessageUtils.setInvalidRequestParameterError(response, "comparisonLeftDataType is illegal.");
                        return true;
                    }
                }

                // 比較条件設定処理.
                String comparison = (String) request.getExtras().get("comparison");
                if (comparison != null) {
                    trigger.setComparision(comparison);
                }

                // 比較値(右辺)設定処理.
                String comparisonRight = (String) request.getExtras().get("comparisonRight");
                if (comparisonRight != null) {
                    trigger.setComparisionRight(comparisonRight);
                }

                // 比較値(右辺)データタイプ設定処理.
                String comparisonRightDataType = (String) request.getExtras().get("comparisonRightDataType");
                if (comparisonRightDataType != null) {
                    if (ComparisonUtil.checkDataType(comparisonRightDataType)) {
                        trigger.setComparisionRightDataType(comparisonRightDataType);
                    } else {
                        MessageUtils.setInvalidRequestParameterError(response, "comparisonRightDataType is illegal.");
                        return true;
                    }
                }

                // トリガー情報保存.
                mRule.setTrigger(trigger);
                ((RuleEngineMessageService) getContext()).updateRuleData(mRule);
                // 応答処理.
                setResult(response, DConnectMessage.RESULT_OK);
                return true;
            }
        });

        // DELETE /rule/trigger
        addApi(new DeleteApi() {
            @Override
            public String getAttribute() {
                return "trigger";
            }

            @Override
            public boolean onRequest(final Intent request, final Intent response) {
                String serviceId = (String) request.getExtras().get("serviceId");

                // インターバルタイマー停止.
                stopTimer(serviceId);

                // Triggerを削除.
                mRule.setTrigger(null);
                ((RuleEngineMessageService) getContext()).updateRuleData(mRule);
                // 応答処理.
                setResult(response, DConnectMessage.RESULT_OK);
                return true;
            }
        });

        // POST /rule/triggerInterval
        addApi(new PostApi() {
            @Override
            public String getAttribute() {
                return "triggerInterval";
            }

            @Override
            public boolean onRequest(final Intent request, final Intent response) {
                String serviceId = (String) request.getExtras().get("serviceId");

                // トリガー情報取得.
                Trigger trigger = mRule.getTrigger();
                if (trigger == null) {
                    trigger = new Trigger();
                }

                // Interval & Unit 設定.
                if (setInterval(request, response, trigger)) {
                    // 情報保存.
                    mRule.setTrigger(trigger);
                    ((RuleEngineMessageService) getContext()).updateRuleData(mRule);
                    // 応答処理.
                    setResult(response, DConnectMessage.RESULT_OK);
                }
                return true;
            }
        });

        // POST /rule/description
        addApi(new PostApi() {
            @Override
            public String getAttribute() {
                return "description";
            }

            @Override
            public boolean onRequest(final Intent request, final Intent response) {
                String serviceId = (String) request.getExtras().get("serviceId");

                // ルール詳細説明設定処理.
                String description = (String) request.getExtras().get("description");
                if (description != null) {
                    mRule.setRuleDescription(description);
                    setResult(response, DConnectMessage.RESULT_OK);
                } else {
                    mRule.setRuleDescription("No description setting.");
                }
                ((RuleEngineMessageService) getContext()).updateRuleData(mRule);
                return true;
            }
        });


    }

    /**
     * Device Connect ManagerのWebSocketサーバーに接続.
     */
    private void connectWebSocket() {
        if (!mSDK.isConnectedWebSocket()) {
            mSDK.connectWebSocket(new DConnectSDK.OnWebSocketListener() {
                @Override
                public void onOpen() {

                }

                @Override
                public void onClose() {

                }

                @Override
                public void onError(Exception e) {

                }
            });
        }
    }

    class AndEstablishment {
        /** ルールサービスID. */
        private String mRuleServiceId;
        /** 成立時刻. */
        private Calendar mEstablishmentTime;

        /**
         * ルールサービスID取得.
         * @return ルールサービスID.
         */
        String getRuleServiceId() {
            return mRuleServiceId;
        }

        /**
         * 成立時刻取得.
         * @return 成立時刻(Calendarクラス).
         */
        Calendar getEstablishmentTime() {
            return mEstablishmentTime;
        }

        /**
         * ルールサービスID設定.
         * @param ruleServiceId ルールサービスID.
         */
        void setRuleServiceId(String ruleServiceId) {
            mRuleServiceId = ruleServiceId;
        }

        /**
         * 成立時刻設定.
         * @param establishmentTime 成立時刻(Calendarクラス).
         */
        public void setEstablishmentTime(Calendar establishmentTime) {
            mEstablishmentTime = establishmentTime;
        }

        /**
         * 成立時刻設定.
         * @param timestamp 成立時刻（RFC3339文字列）.
         * @return true(設定完了) / false (変換エラー).
         */
        boolean setEstablishmentTime(final String timestamp) {
            Calendar calendar = RFC3339DateUtils.toCalendar(timestamp);
            if (calendar == null) {
                return false;
            } else {
                mEstablishmentTime = RFC3339DateUtils.toCalendar(timestamp);
                return true;
            }
        }
    }

    /**
     * トリガーインターバル・単位設定.
     * @param request request.
     * @param response response.
     * @param trigger trigger.
     * @return true / false.
     */
    private boolean setInterval(final Intent request, final Intent response, final Trigger trigger) {
        // トリガーインターバル設定処理.
        Float triggerInterval = parseFloat(request, "triggerInterval");
        if (triggerInterval == null) {
            MessageUtils.setInvalidRequestParameterError(response, "triggerIntervalUnit is illegal.");
            return false;
        }
        Long interval = triggerInterval.longValue();
        if (interval < 0) {
            MessageUtils.setInvalidRequestParameterError(response, "triggerInterval is illegal.");
            return false;
        } else {
            // トリガーインターバル設定.
            trigger.setInterval(interval);
        }

        // トリガーインターバル単位設定処理.
        String triggerIntervalUnit = (String) request.getExtras().get("triggerIntervalUnit");
        if (triggerIntervalUnit != null) {
            if (TimeUnitUtil.checkTimeUnit(triggerIntervalUnit)) {
                // トリガーインターバル単位設定.
                trigger.setIntervalUnit(triggerIntervalUnit);
            } else {
                MessageUtils.setInvalidRequestParameterError(response, "triggerIntervalUnit is illegal.");
                return false;
            }
        }

        // トリガーインターバル開始タイミング設定処理.
        String referenceTime = (String) request.getExtras().get("triggerIntervalReferenceTime");
        if (referenceTime != null) {
            switch (referenceTime) {
                case TimerUtil.REF_NO_SETTING:
                case TimerUtil.REF_00_SECONDS:
                case TimerUtil.REF_10_SECONDS:
                case TimerUtil.REF_20_SECONDS:
                case TimerUtil.REF_30_SECONDS:
                case TimerUtil.REF_40_SECONDS:
                case TimerUtil.REF_50_SECONDS:
                    trigger.setReferenceTime(referenceTime);
                    break;
                default:
                    MessageUtils.setInvalidRequestParameterError(response, "triggerIntervalReferenceTime is illegal.");
                    return false;
            }
        }
        return true;
    }

    /**
     * 解析処理.
     * @param request リクエスト.
     */
    private void executeProcess(final Intent request, final Map<String, Object> stringObjectMap) {
        // DConnectMessageにキャスト.
        DConnectMessage result = (DConnectMessage) stringObjectMap;

        // REST解析
        Trigger trigger = mRule.getTrigger();

        // 比較条件解析
        ComparisonValue leftValue = getComparisonValue(SELECT_LEFT, result);
        String comparison = trigger.getComparision();
        ComparisonValue rightValue = getComparisonValue(SELECT_RIGHT, result);
        if (leftValue == null || rightValue == null) {
            // エラーのため、処理中断
            return;
        }

        // 比較判定
        int res = ComparisonUtil.judgeComparison(leftValue, comparison, rightValue);
        // 判定結果処理
        if (res == ComparisonUtil.RES_TRUE) {
            // 条件成立をイベントにて通知.
            Event event = EventManager.INSTANCE.getEvent(request);
            Intent message = EventManager.createEventMessage(event);
            Bundle root = message.getExtras();
            Bundle coomparisonResult = new Bundle();
            coomparisonResult.putString("ruleServiceId", (String) request.getExtras().get("serviceId"));
            coomparisonResult.putBoolean("result", true);
            coomparisonResult.putString("timestamp", nowTimeStampString());
            root.putBundle("coomparisonResult", coomparisonResult);
            message.putExtras(root);
            sendEvent(message, event.getAccessToken());

            // Operation実行.
            if (mRule.getOperations().size() != 0) {
                // Operation実行.
                executeOperation();
            }
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /**
     * Operation実行.
     */
    private void executeOperation() {
        final Context context = getContext();
        // Operation配列取得
        List<Operation> operations = mRule.getOperations();

        for (Operation operation : operations) {
            // REST
            final String rest = operation.getRest();
            // アクション
            final String action = operation.getAction();
            // パラメータ
            String parameter = operation.getParameter();
            String[] params = parameter.split("&");
            Map<String, Object> parameters = new HashMap<>();
            String serviceId = null;
            for (String param : params) {
                int index = param.indexOf("=");
                String key = param.substring(0, index);
                String value = param.substring(index + 1);
                if (key.contains("serviceId")) {
                    serviceId = value;
                } else {
                    parameters.put(key, value);
                }
            }

//            // serviceId 検索.
//            int position = rest.indexOf(GOTAPI_PHRASE) + GOTAPI_PHRASE.length();
//            String[] token = rest.substring(position).split("/");
//            String profile = token[0];
//
//            for (DConnectHelper.ServiceInfo service : mServiceInfos) {
//                if (service.scopes != null) {
//                    for (String scope : service.scopes) {
//                        if (profile.equalsIgnoreCase(scope)) {
//                            serviceId = service.id;
//                            break;
//                        }
//                    }
//                }
//            }

            if (serviceId == null) {
                // エラー通知 (service not found).
                String errorStatus = " Rule execute error. (Operation - Service not found.)";
                setErrorStatus(errorStatus);
                // エラー情報通知
                ((RuleEngineMessageService) getContext()).sendEventErrorStatus(this.getService().getId(), mRule.getErrorStarus(), mRule.getErrorTimestamp());

                // ServiceDiscovery再発行.
                fetchServices();
                return;
            }

            final String operationServiceId = serviceId;
            final Map<String, Object> operationParameters = parameters;
            final String id = this.getService().getId();
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    Utils.sendRequest(context, action, rest, operationServiceId, operationParameters, new DConnectHelper.FinishCallback<Map<String, Object>>() {
                        @Override
                        public void onFinish(Map<String, Object> stringObjectMap, Exception error) {
                            if (error != null) {
                                // エラー通知.
                                String errorStatus = "Rule operation execute error. errorCode = " + stringObjectMap.get("errorCode") + " (" + stringObjectMap.get("errorMessage") + ")";
                                setErrorStatus(errorStatus);
                                // エラー情報通知
                                ((RuleEngineMessageService) getContext()).sendEventErrorStatus(id, mRule.getErrorStarus(), mRule.getErrorTimestamp());
                            }
                        }
                    });
                }
            });
        }
    }

    /**
     * 条件判定用REST実行処理.
     * @param request リクエスト.
     * @param action アクション.
     * @param rest REST.
     * @param parameter パラメーター.
     */
    private void execRest(final Intent request, final String action, final String rest, final String parameter) {
        Context context = getContext();
        String serviceId = null;
        // パラメータ格納.
        String[] params = parameter.split("&");
        Map<String, Object> parameters = new HashMap<>();
        for (String param : params) {
            int index = param.indexOf("=");
            String key = param.substring(0, index);
            String value = param.substring(index + 1);
            if (key.contains("serviceId")) {
                serviceId = value;
            } else {
                parameters.put(key, value);
            }
        }

//        int index = rest.indexOf(GOTAPI_PHRASE) + GOTAPI_PHRASE.length();
//        String[] token = rest.substring(index).split("/");
//        String profile = token[0];
//        for (DConnectHelper.ServiceInfo service : mServiceInfos) {
//            if (service.scopes != null) {
//                for (String scope : service.scopes) {
//                    if (profile.equalsIgnoreCase(scope)) {
//                        serviceId = service.id;
//                        break;
//                    }
//                }
//            }
//        }

        if (serviceId == null) {
            // エラー通知 (service not found).
            String errorStatus = " Rule execute error. (Service not found.)";
            setErrorStatus(errorStatus);
            // エラー情報通知
            ((RuleEngineMessageService) getContext()).sendEventErrorStatus(this.getService().getId(), mRule.getErrorStarus(), mRule.getErrorTimestamp());

            // ServiceDiscovery再発行.
            fetchServices();
            return;
        }

        final String id = this.getService().getId();
        Utils.sendRequest(context, action, rest, serviceId, parameters, new DConnectHelper.FinishCallback<Map<String, Object>>() {
            @Override
            public void onFinish(Map<String, Object> stringObjectMap, Exception error) {
                if (error != null) {
                    // エラー通知.
                    String errorStatus = "Rule operation execute error. errorCode = " + stringObjectMap.get("errorCode") + " (" + stringObjectMap.get("errorMessage") + ")";
                    setErrorStatus(errorStatus);
                    // エラー情報通知
                    ((RuleEngineMessageService) getContext()).sendEventErrorStatus(id, mRule.getErrorStarus(), mRule.getErrorTimestamp());
                } else {
                    executeProcess(request, stringObjectMap);
                }
            }
        });
    }

    /**
     * AND成立判定処理.
     * @param serviceId ルールserviceID.
     * @param timestamp タイムスタンプ.
     */
    private void andProcess(final String serviceId, final String timestamp) {
        // 受信イベント分の格納処理.
        boolean findFlag = false;
        if (mAndEstablishmentList.size() != 0) {
            for (int i = 0; i < mAndEstablishmentList.size(); i++) {
                AndEstablishment ae = mAndEstablishmentList.get(i);
                if (ae.getRuleServiceId().equals(serviceId)) {
                    ae.setEstablishmentTime(timestamp);
                    mAndEstablishmentList.set(i, ae);
                    findFlag = true;
                    break;
                }
            }
            if (findFlag) { // 該当ルールサービスID存在.
                Calendar compTime = RFC3339DateUtils.toCalendar(timestamp);
                // 時間単位変換.
                String unit = mRule.getAndRule().getJudgementTimeUnit();
                long judgementTime;
                if (unit == null) {
                    // 単位をmSecとして扱う.
                    judgementTime = mRule.getAndRule().getJudgementTime();
                } else {
                    // 単位変換.
                    judgementTime = TimeUnitUtil.changeMSec(mRule.getAndRule().getJudgementTime(), unit);
                }
                // 比較時刻算出.
                long diffTime = compTime.getTimeInMillis() - judgementTime;
                Calendar calDiff = Calendar.getInstance();
                calDiff.clear();
                calDiff.setTime(new Date(diffTime));

                // 成立判定.
                int judgeCount = 0;
                for(AndEstablishment ae : mAndEstablishmentList) {
                    if (ae.getEstablishmentTime().compareTo(calDiff) >= 0) {    // 比較時間に等しいか、以降の時刻.
                        judgeCount++;
                    }
                }
                if (judgeCount == mAndEstablishmentList.size()) {
                    // 条件成立 Operation処理.
                    executeOperation();
                }
            }
        }
    }

    /**
     * エラーステータス取得.
     * @return ErrorStatus構造体.
     */
    public ErrorStatus getErrorStatus() {
        ErrorStatus status = new ErrorStatus();
        status.setStatus(mRule.getErrorStarus());
        status.setTimestamp(mRule.getErrorTimestamp());
        return status;
    }

    /**
     * エラーステータス設定.
     * @param errorStatus エラーステータス.
     */
    public void setErrorStatus(final String errorStatus) {
        mRule.setErrorTimestamp(nowTimeStampString());
        mRule.setErrorStarus(errorStatus);
        ((RuleEngineMessageService) getContext()).updateRuleData(mRule);
    }

    /**
     * ルール設定内容取得.
     * @return ルール構造体.
     */
    public Rule getRule() {
        return mRule;
    }

    /**
     * 比較値データ型変換処理.
     * @param selectLR 右辺左辺判定値("LEFT" or "RIGHT").
     * @param jsonResult JSON設定時、応答データ.
     * @return 変換データ構造体.
     */
    private ComparisonValue getComparisonValue(final String selectLR, final DConnectMessage jsonResult) {
        // トリガー構造体取得
        Trigger trigger = mRule.getTrigger();
        // 変換データ構造体
        ComparisonValue value;
        // 比較値（String型）
        String comparisionValueString;

        // 右辺左辺判定
        switch (selectLR) {
            case SELECT_LEFT:
                // 変換データ構造体初期化.
                value = new ComparisonValue(trigger.getComparisionLeftDataType());
                // 比較値取得.
                comparisionValueString = trigger.getComparisionLeft();
                break;
            case SELECT_RIGHT:
                // 変換データ構造体初期化.
                value = new ComparisonValue(trigger.getComparisionRightDataType());
                // 比較値取得.
                comparisionValueString = trigger.getComparisionRight();
                break;
            default:
                return null;
        }

        // 比較値変換
        String[] comparisionValueList = exchangeComparisionValueList(comparisionValueString);
        switch(value.getDataType()) {
            case ComparisonUtil.TYPE_INT:
                if (comparisionValueList == null) {
                    value.setDataInt(ComparisonValue.FIRST, Integer.parseInt(comparisionValueString));
                } else {
                    for (int i = 0; i < 2; i++) {
                        value.setDataInt(i, Integer.parseInt(comparisionValueList[i]));
                    }
                }
                break;
            case ComparisonUtil.TYPE_LONG:
                if (comparisionValueList == null) {
                    value.setDataLong(ComparisonValue.FIRST, Long.parseLong(comparisionValueString));
                } else {
                    for (int i = 0; i < 2; i++) {
                        value.setDataLong(i, Long.parseLong(comparisionValueList[i]));
                    }
                }
                break;
            case ComparisonUtil.TYPE_FLOAT:
                if (comparisionValueList == null) {
                    value.setDataFloat(ComparisonValue.FIRST, Float.parseFloat(comparisionValueString));
                } else {
                    for (int i = 0; i < 2; i++) {
                        value.setDataFloat(i, Float.parseFloat(comparisionValueList[i]));
                    }
                }
                break;
            case ComparisonUtil.TYPE_DOUBLE:
                if (comparisionValueList == null) {
                    value.setDataDouble(ComparisonValue.FIRST, Double.parseDouble(comparisionValueString));
                } else {
                    for (int i = 0; i < 2; i++) {
                        value.setDataDouble(i, Double.parseDouble(comparisionValueList[i]));
                    }
                }
                break;
            case ComparisonUtil.TYPE_STRING:
            case ComparisonUtil.TYPE_DATE:
            case ComparisonUtil.TYPE_TIME:
            case ComparisonUtil.TYPE_DATE_TIME:
                value.setDataString(ComparisonValue.FIRST, comparisionValueString);
                break;
            case ComparisonUtil.TYPE_JSON_INT:
            case ComparisonUtil.TYPE_JSON_FLOAT:
            case ComparisonUtil.TYPE_JSON_DOUBLE:
            case ComparisonUtil.TYPE_JSON_STRING:
            case ComparisonUtil.TYPE_JSON_DATE_TIME:
                // JSONフォーマット解析用分解
                String[] jsonFormat = comparisionValueString.split(".");
                if (jsonFormat.length == 0) {
                    // エラー処理
                    String errorStatus = " JSON format error. (Tag not found.)";
                    switch (selectLR) {
                        case SELECT_LEFT:
                            errorStatus = "ComparisionLeft" + errorStatus;
                            break;
                        case SELECT_RIGHT:
                            errorStatus = "ComparisionRight" + errorStatus;
                            break;
                    }
                    // エラー情報格納
                    setErrorStatus(errorStatus);
                    // エラー情報通知
                    ((RuleEngineMessageService) getContext()).sendEventErrorStatus(this.getService().getId(), mRule.getErrorStarus(), mRule.getErrorTimestamp());
                    return null;
                }

                if (jsonFormat.length == 1) {
                    boolean result = false;
                    try {
                        String check = jsonResult.getString(jsonFormat[0]);
                        switch (value.getDataType()) {
                            case ComparisonUtil.TYPE_JSON_INT:
                                result = value.setDataInt(ComparisonValue.FIRST, Integer.valueOf(check));
                                break;
                            case ComparisonUtil.TYPE_JSON_FLOAT:
                                result = value.setDataFloat(ComparisonValue.FIRST, Float.valueOf(check));
                                break;
                            case ComparisonUtil.TYPE_JSON_DOUBLE:
                                result = value.setDataDouble(ComparisonValue.FIRST, Double.valueOf(check));
                                break;
                            case ComparisonUtil.TYPE_JSON_STRING:
                            case ComparisonUtil.TYPE_JSON_DATE_TIME:
                                result = value.setDataString(ComparisonValue.FIRST, check);
                                break;
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        result = false;
                    }
                    if (!result) {
                        // エラー処理
                        String errorStatus = " data type exchange error.";
                        switch (selectLR) {
                            case SELECT_LEFT:
                                errorStatus = "ComparisionLeft" + errorStatus;
                                break;
                            case SELECT_RIGHT:
                                errorStatus = "ComparisionRight" + errorStatus;
                                break;
                        }
                        // エラー情報格納
                        setErrorStatus(errorStatus);
                        // エラー情報通知
                        ((RuleEngineMessageService) getContext()).sendEventErrorStatus(this.getService().getId(), mRule.getErrorStarus(), mRule.getErrorTimestamp());
                        return null;
                    }

                } else {
                    boolean result = false;
                    // JSON複数階層解析
                    try {
                        // 階層処理用JSONオブジェクト格納変数
                        DConnectMessage jsonObject = null;
                        // 階層処理用JSON配列オブジェクト格納変数
                        List<Object> jsonArray = null;
                        // 階層処理用JSON配列オブジェクトリードインデックス
                        int arrayIndex = -1;
                        // JSON階層数取得
                        int count = jsonResult.size();
                        for (int i = 0; i < count; i++) {
                            // 名前取得
                            String jsonParam = jsonFormat[i];
                            if (jsonObject == null && jsonArray == null) {
                                // 初回動作
                                if (!jsonParam.contains("[")) {
                                    jsonObject = jsonResult.getMessage(jsonParam);
                                } else {
                                    int firstTag = jsonParam.indexOf("[");
                                    String param = jsonParam.substring(0, firstTag - 1);
                                    int lastTag  = jsonParam.indexOf("]");
                                    // 配列リード位置取得
                                    String indexString = jsonParam.substring(firstTag - 1, lastTag - 1);
                                    arrayIndex = Integer.parseInt(indexString);
                                    // JSON配列オブジェクト取得
                                    jsonArray = jsonResult.getList(param);
                                }
                            } else if (jsonArray == null) {
                                // JSONオブジェクト解析
                                if (i == count - 1) {
                                    // 最終要素
                                    switch (value.getDataType()) {
                                        case ComparisonUtil.TYPE_JSON_INT:
                                            result = value.setDataInt(ComparisonValue.FIRST, jsonObject.getInt(jsonParam));
                                            break;
                                        case ComparisonUtil.TYPE_JSON_FLOAT:
                                            result = value.setDataFloat(ComparisonValue.FIRST, Float.valueOf(jsonObject.getString(jsonParam)));
                                            break;
                                        case ComparisonUtil.TYPE_JSON_DOUBLE:
                                            result = value.setDataDouble(ComparisonValue.FIRST, jsonObject.getDouble(jsonParam));
                                            break;
                                        case ComparisonUtil.TYPE_JSON_STRING:
                                        case ComparisonUtil.TYPE_JSON_DATE_TIME:
                                            result = value.setDataString(ComparisonValue.FIRST, jsonObject.getString(jsonParam));
                                            break;
                                    }
                                } else if (!jsonParam.contains("[")) {
                                    jsonObject = jsonObject.getMessage(jsonParam);
                                } else {
                                    int firstTag = jsonParam.indexOf("[");
                                    String param = jsonParam.substring(0, firstTag - 1);
                                    int lastTag  = jsonParam.indexOf("]");
                                    // 配列リード位置取得
                                    String indexString = jsonParam.substring(firstTag - 1, lastTag - 1);
                                    arrayIndex = Integer.parseInt(indexString);
                                    // JSON配列オブジェクト取得
                                    jsonArray = jsonObject.getList(param);
                                    jsonObject = null;
                                }
                            } else {
                                // JSON配列解析
                                if (i == count - 1) {
                                    DConnectMessage msg = ((DConnectMessage) jsonArray.get(arrayIndex));
                                    switch (value.getDataType()) {
                                        case ComparisonUtil.TYPE_JSON_INT:
                                            result = value.setDataInt(ComparisonValue.FIRST, msg.getInt(jsonParam));
                                            break;
                                        case ComparisonUtil.TYPE_JSON_FLOAT:
                                            result = value.setDataFloat(ComparisonValue.FIRST, Float.valueOf(msg.getString(jsonParam)));
                                            break;
                                        case ComparisonUtil.TYPE_JSON_DOUBLE:
                                            result = value.setDataDouble(ComparisonValue.FIRST, msg.getDouble(jsonParam));
                                            break;
                                        case ComparisonUtil.TYPE_JSON_STRING:
                                        case ComparisonUtil.TYPE_JSON_DATE_TIME:
                                            result = value.setDataString(ComparisonValue.FIRST, msg.getString(jsonParam));
                                            break;
                                    }
                                } else if (!jsonParam.contains("[")) {
                                    jsonObject = ((DConnectMessage) jsonArray.get(arrayIndex)).getMessage(jsonParam);
                                    jsonArray = null;
                                    arrayIndex = -1;
                                } else {
                                    int firstTag = jsonParam.indexOf("[");
                                    String param = jsonParam.substring(0, firstTag - 1);
                                    int lastTag  = jsonParam.indexOf("]");
                                    String indexString = jsonParam.substring(firstTag - 1, lastTag - 1);
                                    // JSON配列オブジェクト取得
                                    jsonArray = ((DConnectMessage) jsonArray.get(arrayIndex)).getList(jsonParam);
                                    // 配列リード位置取得
                                    arrayIndex = Integer.parseInt(indexString);
                                    jsonObject = null;
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        result = false;
                    }
                    if (!result) {
                        // エラー処理
                        String errorStatus = " result not match name.";
                        switch (selectLR) {
                            case SELECT_LEFT:
                                errorStatus = "ComparisionLeft" + errorStatus;
                                break;
                            case SELECT_RIGHT:
                                errorStatus = "ComparisionRight" + errorStatus;
                                break;
                        }
                        setErrorStatus(errorStatus);
                        ((RuleEngineMessageService) getContext()).sendEventErrorStatus(this.getService().getId(), mRule.getErrorStarus(), mRule.getErrorTimestamp());
                        return null;
                    }
                }
                break;
        }
        // 変換結果返却
        return value;
    }

    /**
     * 比較値文字列のトークン切り出し処理.
     * @param comparisionValueString 比較値文字列.
     * @return トークン切り出しが必要な文字列の場合は分割した文字列のリスト。トークンが無い場合はnull.
     */
    private String[] exchangeComparisionValueList(final String comparisionValueString) {
        if (comparisionValueString.contains(",")) {
            StringTokenizer st = new StringTokenizer(comparisionValueString, ",");
            String[] comparisionValueList = new String[st.countTokens()];
            int index = 0;
            while (st.hasMoreTokens()) {
                comparisionValueList[index++] = st.nextToken();
            }
            return comparisionValueList;
        } else {
            return null;
        }
    }

    /**
     * Device Connect Managerへの接続処理.
     */
    private void connectDCM() {
        final Context context = getContext();
        final SettingData setting = SettingData.getInstance(context);
        if (!setting.active) {
            return;
        }

        Utils.availability(context, new DConnectHelper.FinishCallback<Void>() {
            @Override
            public void onFinish(Void aVoid, Exception error) {
                if (error == null) {
                    setting.save();
                    Utils.registerEvent(context, new DConnectHelper.FinishCallback<Void>() {
                        @Override
                        public void onFinish(Void aVoid, Exception error) {
                            if (error != null) {
                                setting.save();
                            } else {
                                fetchServices();
                            }
                        }
                    });
                }
            }
        });
    }


    private CountDownLatch fetchServicesLatch = new CountDownLatch(1);
    private void fetchServices() {
        final Context context = getContext();
        Utils.fetchServices(context, new DConnectHelper.FinishCallback<List<DConnectHelper.ServiceInfo>>() {
            @Override
            public void onFinish(List<DConnectHelper.ServiceInfo> serviceInfos, Exception error) {
                mServiceInfos = serviceInfos;
                mManagerStart = true;
                fetchServicesLatch.countDown();
            }
        });

        try {
            fetchServicesLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    @Override
    public String getProfileName() {
        return PROFILE_NAME;
    }

    private final Map<String, TimerTask> mTimerTasks = new ConcurrentHashMap<>();
    private final Timer mTimer = new Timer();

    private void startTimer(final String taskId, final TimerTask task, final long interval, final String refTime, final String delayOccurrence) {
        synchronized (mTimerTasks) {
            stopTimer(taskId);
            mTimerTasks.put(taskId, task);
            Date delayDate = TimerUtil.getStartTimerTime(refTime).getTime();
            if (delayOccurrence.contains(Operation.STACK)) {
                mTimer.scheduleAtFixedRate(task, delayDate, interval);
            } else {
                mTimer.schedule(task, delayDate, interval);
            }
        }
    }

    private void stopTimer(final String taskId) {
        synchronized (mTimerTasks) {
            TimerTask timer = mTimerTasks.remove(taskId);
            if (timer != null) {
                timer.cancel();
            }
        }
    }
}