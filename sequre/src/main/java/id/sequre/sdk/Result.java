package id.sequre.sdk;

public class Result {
    protected Status status;
    protected Float score;
    protected String message;

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

    @Override
    public String toString() {
        return "Result{" +
                "status=" + status +
                ", score=" + score +
                ", message='" + message + '\'' +
                '}';
    }
}
