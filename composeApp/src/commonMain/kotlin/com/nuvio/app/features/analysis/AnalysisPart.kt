package com.nuvio.app.features.analysis

/**
 * Readiness of one analysis output.
 * The live download-job state stays with the mobile scheduler. Desktop has no analyser host.
 */
enum class AnalysisPart { Ready, NotFound, NeedsMoreEpisodes, Off, Pending, NotChecked }
