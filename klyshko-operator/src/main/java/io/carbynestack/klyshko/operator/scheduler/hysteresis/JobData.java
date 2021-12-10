package io.carbynestack.klyshko.operator.scheduler.hysteresis;

public record JobData(JobParameters jobParameters, VcpJobManager.JobState jobState) {
}
