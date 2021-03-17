// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "summaryengine.h"
#include <vespa/vespalib/data/slime/slime.h>
#include <vespa/vespalib/util/size_literals.h>

#include <vespa/log/log.h>
LOG_SETUP(".proton.summaryengine.summaryengine");

using namespace search::engine;
using namespace proton;
using vespalib::Memory;
using vespalib::slime::Inspector;

namespace {

Memory DOCSUMS("docsums");

class DocsumTask : public vespalib::Executor::Task {
private:
    SummaryEngine       & _engine;
    DocsumClient        & _client;
    DocsumRequest::Source _request;

public:
    DocsumTask(SummaryEngine & engine, DocsumRequest::Source request, DocsumClient & client)
        : _engine(engine),
          _client(client),
          _request(std::move(request))
    {
    }

    void run() override {
        _client.getDocsumsDone(_engine.getDocsums(_request.release()));
    }
};

uint32_t getNumDocs(const DocsumReply &reply) {
    if (reply._root) {
        const Inspector &root = reply._root->get();
        return root[DOCSUMS].entries();
    } else {
        return reply.docsums.size();
    }
}

VESPA_THREAD_STACK_TAG(summary_engine_executor)

} // namespace anonymous

namespace proton {

SummaryEngine::DocsumMetrics::DocsumMetrics()
    : metrics::MetricSet("docsum", {}, "Docsum metrics", nullptr),
      count("count", {{"logdefault"}}, "Docsum requests handled", this),
      docs("docs", {{"logdefault"}}, "Total docsums returned", this),
      latency("latency", {{"logdefault"}}, "Docsum request latency", this)
{
}

SummaryEngine::DocsumMetrics::~DocsumMetrics() = default;

SummaryEngine::SummaryEngine(size_t numThreads)
    : _lock(),
      _closed(false),
      _handlers(),
      _executor(numThreads, 128_Ki, summary_engine_executor),
      _metrics(std::make_unique<DocsumMetrics>())
{ }

SummaryEngine::~SummaryEngine()
{
    _executor.shutdown();
}

void
SummaryEngine::close()
{
    LOG(debug, "Closing summary engine");
    {
        std::lock_guard<std::mutex> guard(_lock);
        _closed = true;
    }
    LOG(debug, "Handshaking with task manager");
    _executor.sync();
}

ISearchHandler::SP
SummaryEngine::putSearchHandler(const DocTypeName &docTypeName, const ISearchHandler::SP & searchHandler)
{
    std::lock_guard<std::mutex> guard(_lock);
    return _handlers.putHandler(docTypeName, searchHandler);
}

ISearchHandler::SP
SummaryEngine::getSearchHandler(const DocTypeName &docTypeName)
{
    std::lock_guard<std::mutex> guard(_lock);
    return _handlers.getHandler(docTypeName);
}

ISearchHandler::SP
SummaryEngine::removeSearchHandler(const DocTypeName &docTypeName)
{
    std::lock_guard<std::mutex> guard(_lock);
    return _handlers.removeHandler(docTypeName);
}

DocsumReply::UP
SummaryEngine::getDocsums(DocsumRequest::Source request, DocsumClient & client)
{
    if (_closed) {
        LOG(warning, "Receiving docsumrequest after engine has been shutdown");
        auto ret = std::make_unique<DocsumReply>();

        // TODO: Notify closed.

        return ret;
    }
    auto task =std::make_unique<DocsumTask>(*this, std::move(request), client);
    _executor.execute(std::move(task));
    return DocsumReply::UP();
}

DocsumReply::UP
SummaryEngine::getDocsums(DocsumRequest::UP req)
{
    DocsumReply::UP reply = std::make_unique<DocsumReply>();

    if (req) {
        ISearchHandler::SP searchHandler = getSearchHandler(DocTypeName(*req));
        if (searchHandler) {
            reply = searchHandler->getDocsums(*req);
        } else {
            HandlerMap<ISearchHandler>::Snapshot snapshot;
            {
                std::lock_guard<std::mutex> guard(_lock);
                snapshot = _handlers.snapshot();
            }
            if (snapshot.valid()) {
                reply = snapshot.get()->getDocsums(*req); // use the first handler
            }
        }
        updateDocsumMetrics(vespalib::to_s(req->getTimeUsed()), getNumDocs(*reply));
    }
    reply->request = std::move(req);

    return reply;
}

void
SummaryEngine::updateDocsumMetrics(double latency_s, uint32_t numDocs)
{
    std::lock_guard guard(_lock);
    DocsumMetrics & m = static_cast<DocsumMetrics &>(*_metrics);
    m.count.inc();
    m.docs.inc(numDocs);
    m.latency.set(latency_s);
}

} // namespace proton
