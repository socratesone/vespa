// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "statusmetricconsumer.h"
#include <boost/lexical_cast.hpp>
#include <vespa/metrics/jsonwriter.h>
#include <vespa/metrics/textwriter.h>
#include <vespa/metrics/xmlwriter.h>
#include <vespa/metrics/metricmanager.h>
#include <vespa/storageapi/messageapi/storagemessage.h>
#include <vespa/vespalib/stllike/asciistream.h>
#include <vespa/vespalib/util/xmlstream.h>

#include <vespa/log/log.h>
LOG_SETUP(".status.metricreporter");

namespace storage {

StatusMetricConsumer::StatusMetricConsumer(
        StorageComponentRegister& compReg, metrics::MetricManager& manager,
        const std::string& name)
    : framework::StatusReporter("metrics", "Performance metrics"),
      _manager(manager),
      _component(compReg, "statusmetricsconsumer"),
      _name(name),
      _lock(),
      _startTime(_component.getClock().getTimeInSeconds()),
      _processedTime(0)
{
    LOG(debug, "Started metrics consumer");
    setlocale(LC_NUMERIC, "");
    _component.registerMetricUpdateHook(*this, framework::SecondTime(3600));
    _component.registerStatusPage(*this);
}

StatusMetricConsumer::~StatusMetricConsumer() = default;

void
StatusMetricConsumer::updateMetrics(const MetricLockGuard & guard)
{
    metrics::MemoryConsumption::UP mc(_manager.getMemoryConsumption(guard));
    // TODO is this hook needed anymore?
}

vespalib::string
StatusMetricConsumer::getReportContentType(const framework::HttpUrlPath& path) const
{
    if (!path.hasAttribute("format")) {
        return "text/plain";
    }

    if (path.getAttribute("format") == "xml") {
        return "application/xml";
    }

    if (path.getAttribute("format") == "text") {
        return "text/plain";
    }

    if (path.getAttribute("format") == "json") {
        return "application/json";
    }

    return "text/plain";
}

bool
StatusMetricConsumer::reportStatus(std::ostream& out,
                                   const framework::HttpUrlPath& path) const
{
        // Update metrics unless 'dontcallupdatehooks' is 1. Update
        // snapshot metrics too, if callsnapshothooks is set to 1.
    if (path.get("dontcallupdatehooks", 0) == 0) {
        bool updateSnapshotHooks = path.get("callsnapshothooks", 0) == 1;
        LOG(debug, "Updating metrics ahead of status page view%s",
            updateSnapshotHooks ? ", calling snapshot hooks too" : ".");
        _manager.updateMetrics(updateSnapshotHooks);
    } else {
        LOG(debug, "Not calling update hooks as dontcallupdatehooks option has been given");
    }
    framework::SecondTime currentTime(_component.getClock().getTimeInSeconds());
    bool xml = (path.getAttribute("format") == "xml");
    bool json = (path.getAttribute("format") == "json");

    int verbosity(path.get("verbosity", 0));
        // We have to copy unset values if using HTML as HTML version gathers
        // metrics for calculations and thus needs unset values.
    bool copyUnset = (verbosity >= 2);
    bool temporarySnap = (path.get("tmpsnapshot", 0) == 1);

    if (path.hasAttribute("task") && path.getAttribute("task") == "reset") {
        std::lock_guard guard(_lock);
        _manager.reset(currentTime.getTime());
    }

    if (path.hasAttribute("interval")) {
        // Grab the snapshot we want to view more of
        int32_t interval(boost::lexical_cast<int32_t>(path.getAttribute("interval")));
        metrics::MetricLockGuard metricLock(_manager.getMetricLock());
        std::unique_ptr<metrics::MetricSnapshot> generated;
        const metrics::MetricSnapshot* snapshot;
        if (interval == -2) {
            snapshot = &_manager.getActiveMetrics(metricLock);
            _manager.getActiveMetrics(metricLock).setToTime(currentTime.getTime());
        } else if (interval == -1) {
            // "Prime" the metric structure by first fetching the set of active
            // metrics (complete with structure) and resetting these. This
            // leaves us with an empty metrics set to which we can (in order)
            // add the total and the active metrics. If this is not done, non-
            // written metrics won't be included even if copyUnset is true.
            generated = std::make_unique<metrics::MetricSnapshot>(
                    "Total metrics from start until current time", 0,
                    _manager.getActiveMetrics(metricLock).getMetrics(),
                    copyUnset);
            generated->reset(0);
            _manager.getTotalMetricSnapshot(metricLock).addToSnapshot(*generated, currentTime.getTime());
            _manager.getActiveMetrics(metricLock).addToSnapshot(*generated, currentTime.getTime());
            generated->setFromTime(_manager.getTotalMetricSnapshot(metricLock).getFromTime());
            snapshot = generated.get();
        } else if (interval == 0) {
            if (copyUnset) {
                generated = std::make_unique<metrics::MetricSnapshot>(
                        _manager.getTotalMetricSnapshot(metricLock).getName(),
                        0,
                        _manager.getActiveMetrics(metricLock).getMetrics(),
                        true);
                generated->reset(0);
                _manager.getTotalMetricSnapshot(metricLock).addToSnapshot(*generated, currentTime.getTime());
                snapshot = generated.get();
            } else {
                snapshot = &_manager.getTotalMetricSnapshot(metricLock);
            }
        } else {
            if (copyUnset) {
                generated = std::make_unique<metrics::MetricSnapshot>(
                        _manager.getMetricSnapshot(metricLock, interval).getName(), 0,
                        _manager.getActiveMetrics(metricLock).getMetrics(), true);
                generated->reset(0);
                _manager.getMetricSnapshot(metricLock, interval, temporarySnap)
                        .addToSnapshot(*generated, currentTime.getTime());
                snapshot = generated.get();
            } else {
                snapshot = &_manager.getMetricSnapshot(metricLock, interval, temporarySnap);
            }
        }

        std::string consumer = path.getAttribute("consumer", "");
        if (xml) {
            out << "<?xml version=\"1.0\"?>\n";
            vespalib::XmlOutputStream xos(out);
            metrics::XmlWriter xmlWriter(xos, snapshot->getPeriod(), verbosity);
            _manager.visit(metricLock, *snapshot, xmlWriter, consumer);
            out << "\n";
        } else if (json) {
            vespalib::asciistream jsonStreamData;
            vespalib::JsonStream stream(jsonStreamData, true);
            stream << Object() << "metrics";
            metrics::JsonWriter metricJsonWriter(stream);
            _manager.visit(metricLock, *snapshot, metricJsonWriter, consumer);
            stream << End();
            stream.finalize();
            out << jsonStreamData.str();
        } else {
            std::string pattern = path.getAttribute("pattern", ".*");
            metrics::TextWriter textWriter(out, snapshot->getPeriod(), pattern, verbosity > 0);
            _manager.visit(metricLock, *snapshot, textWriter, consumer);
        }
    }

    return true;
}

} // storage
