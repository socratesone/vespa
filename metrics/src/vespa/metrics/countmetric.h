// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * \class CountMetric
 * \ingroup metrics
 *
 * \brief Metric representing a count.
 *
 * A counter metric have the following properties:
 *   - It can never decrease, only increase.
 *   - Logs its value as a count event.
 *   - When summing counts, the counts are added together.
 */

#pragma once

#include "countmetricvalues.h"
#include <vespa/metrics/metric.h>

namespace metrics {

struct AbstractCountMetric : public Metric {
    bool visit(MetricVisitor& visitor, bool tagAsAutoGenerated = false) const override
    {
        return visitor.visitCountMetric(*this, tagAsAutoGenerated);
    }
    virtual MetricValueClass::UP getValues() const = 0;
    virtual bool sumOnAdd() const = 0;
    virtual bool inUse(const MetricValueClass& v) const = 0;

protected:
    AbstractCountMetric(const String& name, Tags dimensions,
                        const String& description, MetricSet* owner = 0)
        : Metric(name, std::move(dimensions), description, owner)
    {
    }

    AbstractCountMetric(const AbstractCountMetric& other, MetricSet* owner)
        : Metric(other, owner)
    {
    }

    void logWarning(const char* msg, const char * op) const;
};

template<typename T, bool SumOnAdd>
class CountMetric : public AbstractCountMetric
{
    using Values = CountMetricValues<T>;
    MetricValueSet<Values> _values;

public:
    CountMetric(const String& name, Tags dimensions,
                const String& description, MetricSet* owner = 0);

    CountMetric(const CountMetric<T, SumOnAdd>& other, CopyType, MetricSet* owner);

    ~CountMetric() override;

    MetricValueClass::UP getValues() const override;

    void set(T value);
    void inc(T value = 1);
    void dec(T value = 1);

    CountMetric & operator+=(const CountMetric &);
    CountMetric & operator-=(const CountMetric &);
    friend CountMetric operator+(const CountMetric & a, const CountMetric & b) {
        CountMetric t(a); t += b; return t;
    }
    friend CountMetric operator-(const CountMetric & a, const CountMetric & b) {
        CountMetric t(a); t -= b; return t;
    }



    CountMetric * clone(std::vector<Metric::UP> &, CopyType type, MetricSet* owner,
                         bool /*includeUnused*/) const override {
        return new CountMetric<T, SumOnAdd>(*this, type, owner);
    }

    T getValue() const { return _values.getValues()._value; }

    void reset() override { _values.reset(); }
    void print(std::ostream&, bool verbose,
               const std::string& indent, uint64_t secondsPassed) const override;

    // Only one metric in valuemetric, so return it on any id.
    int64_t getLongValue(stringref id) const override {
        (void) id;
        return static_cast<int64_t>(getValue());
    }
    double getDoubleValue(stringref id) const override {
        (void) id;
        return static_cast<double>(getValue());
    }

    bool inUse(const MetricValueClass& v) const  override {
        return static_cast<const Values&>(v).inUse();
    }
    bool used() const override { return _values.getValues().inUse(); }
    bool sumOnAdd() const override { return SumOnAdd; }
    void addMemoryUsage(MemoryConsumption&) const override;
    void printDebug(std::ostream&, const std::string& indent) const override;
    void addToPart(Metric&) const override;
    void addToSnapshot(Metric&, std::vector<Metric::UP> &) const override;
};

typedef CountMetric<uint64_t, true> LongCountMetric;

} // metrics

