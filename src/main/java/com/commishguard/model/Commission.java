package com.commishguard.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A single commission and its escrow state. Mutable because the lifecycle advances in place;
 * mutation is serialized by {@code LedgerService}.
 */
public final class Commission {

    private String id;
    private Contract contract;
    private String chatExcerpt;
    private CommissionStatus status = CommissionStatus.QUOTED;

    /** "live" or "mock" — never let a mock capture look real in the evidence trail. */
    private String mode = "mock";
    private boolean demo;

    private String paypalOrderId;
    private String paypalStatus;
    private String approvalUrl;
    private String authorizationId;
    private String captureId;
    private String captureStatus;

    private String deliverableName;
    private long deliverableSize;

    private VerificationResult verification;
    private List<AuditEvent> audit = new ArrayList<>();

    private String createdAt = Instant.now().toString();
    private String updatedAt = createdAt;

    @SuppressWarnings("unused")
    private Commission() {
        // Gson
    }

    public Commission(String id, Contract contract, String chatExcerpt, String mode, boolean demo) {
        this.id = id;
        this.contract = contract;
        this.chatExcerpt = chatExcerpt;
        this.mode = mode;
        this.demo = demo;
    }

    public String getId() {
        return id;
    }

    public Contract getContract() {
        return contract;
    }

    public void setContract(Contract contract) {
        this.contract = contract;
    }

    public String getChatExcerpt() {
        return chatExcerpt;
    }

    public CommissionStatus getStatus() {
        return status;
    }

    public void setStatus(CommissionStatus status) {
        this.status = status;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isDemo() {
        return demo;
    }

    public void setDemo(boolean demo) {
        this.demo = demo;
    }

    public String getPaypalOrderId() {
        return paypalOrderId;
    }

    public void setPaypalOrderId(String paypalOrderId) {
        this.paypalOrderId = paypalOrderId;
    }

    public String getPaypalStatus() {
        return paypalStatus;
    }

    public void setPaypalStatus(String paypalStatus) {
        this.paypalStatus = paypalStatus;
    }

    public String getApprovalUrl() {
        return approvalUrl;
    }

    public void setApprovalUrl(String approvalUrl) {
        this.approvalUrl = approvalUrl;
    }

    public String getAuthorizationId() {
        return authorizationId;
    }

    public void setAuthorizationId(String authorizationId) {
        this.authorizationId = authorizationId;
    }

    public String getCaptureId() {
        return captureId;
    }

    public void setCaptureId(String captureId) {
        this.captureId = captureId;
    }

    public String getCaptureStatus() {
        return captureStatus;
    }

    public void setCaptureStatus(String captureStatus) {
        this.captureStatus = captureStatus;
    }

    public String getDeliverableName() {
        return deliverableName;
    }

    public void setDeliverableName(String deliverableName) {
        this.deliverableName = deliverableName;
    }

    public long getDeliverableSize() {
        return deliverableSize;
    }

    public void setDeliverableSize(long deliverableSize) {
        this.deliverableSize = deliverableSize;
    }

    public VerificationResult getVerification() {
        return verification;
    }

    public void setVerification(VerificationResult verification) {
        this.verification = verification;
    }

    public List<AuditEvent> getAudit() {
        return audit == null ? List.of() : audit;
    }

    public void setAudit(List<AuditEvent> audit) {
        this.audit = audit == null ? new ArrayList<>() : new ArrayList<>(audit);
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public BigDecimal price() {
        return contract == null ? BigDecimal.ZERO : contract.priceUSD();
    }

    public void touch() {
        this.updatedAt = Instant.now().toString();
    }

    public void addAudit(String actor, String action, String detail) {
        if (this.audit == null) {
            this.audit = new ArrayList<>();
        }
        this.audit.add(new AuditEvent(Instant.now().toString(), actor, action, detail));
        if (this.audit.size() > 60) {
            this.audit = new ArrayList<>(this.audit.subList(this.audit.size() - 60, this.audit.size()));
        }
        touch();
    }
}
