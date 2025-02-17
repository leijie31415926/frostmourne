package com.autohome.frostmourne.monitor.contract;

import com.autohome.frostmourne.monitor.contract.enums.AlertTemplateType;

import java.util.Map;

public class RuleContract {

    private String ruleType;

    private Long alarmId;

    private AlertTemplateType alertTemplateType;

    private String alertTemplate;

    private Map<String, String> settings;

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public Long getAlarmId() {
        return alarmId;
    }

    public void setAlarmId(Long alarmId) {
        this.alarmId = alarmId;
    }

    public AlertTemplateType getAlertTemplateType() {
        return alertTemplateType;
    }

    public void setAlertTemplateType(AlertTemplateType alertTemplateType) {
        this.alertTemplateType = alertTemplateType;
    }

    public String getAlertTemplate() {
        return alertTemplate;
    }

    public void setAlertTemplate(String alertTemplate) {
        this.alertTemplate = alertTemplate;
    }

    public Map<String, String> getSettings() {
        return settings;
    }

    public void setSettings(Map<String, String> settings) {
        this.settings = settings;
    }
}
