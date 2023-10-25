package id.sequre.sdk;

public class Result {
    protected Status status;
    protected Float score;
    protected String message;
    protected String timeline;
    protected String qr;

    public Result() {
        status = Status.Canceled;
    }

    public Status getStatus() {
        return status;
    }

    public Float getScore() {
        return score;
    }

    public String getMessage() {
        return message;
    }

    public String getTimeline() {
        return timeline;
    }

    public String getQr() {
        return qr;
    }

    @Override
    public String toString() {
        return "Result{" +
                "status=" + status +
                ", score=" + score +
                ", qr='" + qr + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
