package com.morpheusdata.veeam.utils

import spock.lang.Specification

class VeeamScheduleUtilsSpec extends Specification {

	void "decodeScheduling builds a daily cron from a veeam 13 response"() {
		given:
		def job = JsonUtils.normalize([
			scheduleConfigured: true,
			scheduleEnabled: true,
			jobScheduleOptions: [
				optionsDaily: [enabled: true, kind: 'Everyday', timeOffsetUtc: 7200]
			]
		])

		expect:
		VeeamScheduleUtils.decodeScheduling(job) == '0 0 2 	* * ?'
	}

	void "decodeScheduling builds a daily cron from a veeam 12 response"() {
		given:
		def job = JsonUtils.normalize([
			ScheduleConfigured: 'true',
			ScheduleEnabled: 'true',
			JobScheduleOptions: [
				OptionsDaily: [Enabled: 'true', Kind: 'Everyday', TimeOffsetUtc: '7200']
			]
		])

		expect:
		VeeamScheduleUtils.decodeScheduling(job) == '0 0 2 	* * ?'
	}

	void "decodeScheduling builds a selected days cron"() {
		given:
		def job = JsonUtils.normalize([
			jobScheduleOptions: [
				optionsDaily: [enabled: true, kind: 'SelectedDays', timeOffsetUtc: 3660, days: ['Monday', 'Friday']]
			]
		])

		expect:
		VeeamScheduleUtils.decodeScheduling(job) == '0 1 1 ? * 2,6'
	}

	void "decodeScheduling unwraps the singular child days container"() {
		given:
		def job = JsonUtils.normalize([
			jobScheduleOptions: [
				optionsDaily: [enabled: true, kind: 'SelectedDays', timeOffsetUtc: 0, days: [dayOfWeek: ['Sunday']]]
			]
		])

		expect:
		VeeamScheduleUtils.decodeScheduling(job) == '0 0 0 ? * 1'
	}

	void "decodeScheduling builds a periodic cron"() {
		given:
		def job = JsonUtils.normalize([
			jobScheduleOptions: [
				optionsDaily: [enabled: false],
				optionsMonthly: [enabled: false],
				optionsPeriodically: [enabled: true, fullPeriod: 4]
			]
		])

		expect:
		VeeamScheduleUtils.decodeScheduling(job) == '0 0 4 * * ?'
	}

	void "decodeScheduling returns null when no schedule is enabled"() {
		given:
		def job = JsonUtils.normalize([
			scheduleConfigured: false,
			jobScheduleOptions: [optionsDaily: [enabled: false]]
		])

		expect:
		VeeamScheduleUtils.decodeScheduling(job) == null
	}

	void "decodeScheduling returns null when the job has no schedule options"() {
		expect:
		VeeamScheduleUtils.decodeScheduling([:]) == null
	}
}
