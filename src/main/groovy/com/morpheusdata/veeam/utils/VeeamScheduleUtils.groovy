package com.morpheusdata.veeam.utils

class VeeamScheduleUtils {

	static dayOfWeekList = [
		[index:1, name:'Sunday'],
		[index:2, name:'Monday'],
		[index:3, name:'Tuesday'],
		[index:4, name:'Wednesday'],
		[index:5, name:'Thursday'],
		[index:6, name:'Friday'],
		[index:7, name:'Saturday']
	]

	static monthList = [
		[index:1, name:'January'],
		[index:2, name:'February'],
		[index:3, name:'March'],
		[index:4, name:'April'],
		[index:5, name:'May'],
		[index:6, name:'June'],
		[index:7, name:'July'],
		[index:8, name:'August'],
		[index:9, name:'September'],
		[index:10, name:'October'],
		[index:11, name:'November'],
		[index:12, name:'December']
	]

	//scheduling
	/**
	 * Build a cron representation of a Veeam job schedule.
	 *
	 * @param job a job entity normalized to camelCase by {@link JsonUtils}
	 * @return the cron expression or null when no schedule is enabled
	 */
	static decodeScheduling(job) {
		def rtn
		//build a cron representation
		def scheduleOptions = getScheduleOptions(job)
		def optionsDaily = JsonUtils.get(scheduleOptions, 'optionsDaily')
		def optionsMonthly = JsonUtils.get(scheduleOptions, 'optionsMonthly')
		def optionsPeriodically = JsonUtils.get(scheduleOptions, 'optionsPeriodically')
		//build cron off the type
		if(isEnabled(optionsDaily)) {
			//get the hour offset
			def timeOffset = JsonUtils.toLong(optionsDaily.timeOffsetUtc) ?: 0l
			def hour = ((int)(timeOffset.div(3600l)))
			def minute = ((int)((timeOffset - (hour * 3600l)).div(60)))
			//build the string
			rtn = '0 ' + minute + ' ' + hour
			//get the days of the week
			if(optionsDaily.kind == 'Everyday') {
				rtn = rtn + ' 	* * ?'
			} else {
				def days = JsonUtils.getList(optionsDaily, 'days', 'dayOfWeek')
				def dayList = []
				dayOfWeekList?.each { day ->
					if(days.find { it.toString() == day.name }) {
						dayList << day.index
					}
				}
				rtn = rtn + ' ? * ' + dayList.join(',')
			}
		} else if(isEnabled(optionsMonthly)) {
			def timeOffset = JsonUtils.toLong(optionsMonthly.timeOffsetUtc) ?: 0l
			def hour = ((int)(timeOffset.div(3600l)))
			def minute = ((int)((timeOffset - (hour * 3600l)).div(60)))
			def day = optionsMonthly.dayOfMonth
			//cron can't handle the other style - fourth saturday of month
			//build the string
			rtn = '0 ' + minute + ' ' + hour + ' ' + day
			//get the days of the month
			def monthValues = JsonUtils.getList(optionsMonthly, 'months', 'month')
			def months = []
			monthList?.each { month ->
				if(monthValues.find { it.toString() == month.name })
					months << month.index
			}
			if(months?.size() == 12) {
				rtn = rtn + ' ' + '*'
			} else {
				rtn = rtn + ' ' + months.join(',')
			}
			rtn = rtn + ' ?'
		} else if(isEnabled(optionsPeriodically)) {
			//add continuously support
			def hour = optionsPeriodically.fullPeriod
			//build the string
			rtn = '0 0 ' + hour + ' * * ?'
		}
		return rtn
	}

	/**
	 * Resolve the block holding the schedule options of a job. Veeam nests them under a {@code Standart} (sic)
	 * element, but the wrapper is not always present, so both layouts are supported.
	 *
	 * @param job a job entity normalized to camelCase by {@link JsonUtils}
	 * @return the schedule options block, or null when the job has no schedule options
	 */
	static getScheduleOptions(job) {
		def scheduleOptions = JsonUtils.get(job, 'jobScheduleOptions')
		def standard = JsonUtils.get(scheduleOptions, 'standart')
		return standard != null ? standard : scheduleOptions
	}

	/**
	 * Check whether a schedule options block is enabled. Veeam 13 returns a real JSON boolean where the deprecated
	 * XML representation returned the string "true".
	 *
	 * @param options the schedule options block
	 * @return true when the schedule is enabled
	 */
	static Boolean isEnabled(options) {
		return JsonUtils.get(options, 'enabled')?.toString() == 'true'
	}

}
