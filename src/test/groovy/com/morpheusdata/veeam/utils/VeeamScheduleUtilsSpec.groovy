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

	void "decodeScheduling builds a complete monthly cron"() {
		given:
		def job = JsonUtils.normalize([
			jobScheduleOptions: [
				optionsDaily: [enabled: false],
				optionsMonthly: [enabled: true, timeOffsetUtc: 3600, dayOfMonth: 1, months: ['January', 'July']]
			]
		])

		expect:
		VeeamScheduleUtils.decodeScheduling(job) == '0 0 1 1 1,7 ?'
	}

	void "decodeScheduling uses a wildcard when every month is selected"() {
		given:
		def job = JsonUtils.normalize([
			jobScheduleOptions: [
				optionsDaily: [enabled: false],
				optionsMonthly: [enabled: true, timeOffsetUtc: 0, dayOfMonth: 15,
					months: ['January', 'February', 'March', 'April', 'May', 'June',
						'July', 'August', 'September', 'October', 'November', 'December']]
			]
		])

		expect:
		VeeamScheduleUtils.decodeScheduling(job) == '0 0 0 15 * ?'
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

	void "decodeScheduling reads the schedule options nested under the Standart wrapper"() {
		given:
		def job = JsonUtils.normalize([
			ScheduleConfigured: true,
			ScheduleEnabled: true,
			JobScheduleOptions: [
				Standart: [
					OptionsDaily: [Kind: 'Everyday', TimeOffsetUtc: 7200, Enabled: true],
					OptionsMonthly: [Enabled: false],
					OptionsPeriodically: [Enabled: false]
				]
			]
		])

		expect:
		VeeamScheduleUtils.decodeScheduling(job) == '0 0 2 	* * ?'
	}

	void "decodeScheduling returns null when the job has no schedule options"() {
		expect:
		VeeamScheduleUtils.decodeScheduling([:]) == null
	}
}
