"""原 21 类节点显式懒加载；纯流程不急切加载模型/SQL/Runner SDK。"""
from importlib import import_module


class LazyNodeClass:
    def __init__(self, module, classname):
        self.module, self.classname = module, classname

    def load(self):
        return getattr(import_module(self.module), self.classname)

    def __call__(self, **kwargs):
        return self.load()(**kwargs)

    def __getattr__(self, name):
        return getattr(self.load(), name)


AgentNode = LazyNodeClass('workflow.engine.nodes.agent.agent_node', 'AgentNode')
CodeNode = LazyNodeClass('workflow.engine.nodes.code.code_node', 'CodeNode')
DecisionNode = LazyNodeClass('workflow.engine.nodes.decision.decision_node', 'DecisionNode')
EndNode = LazyNodeClass('workflow.engine.nodes.end.end_node', 'EndNode')
FlowNode = LazyNodeClass('workflow.engine.nodes.flow.flow_node', 'FlowNode')
GlobalVariablesNode = LazyNodeClass('workflow.engine.nodes.global_variables.global_variables_node', 'GlobalVariablesNode')
IFElseNode = LazyNodeClass('workflow.engine.nodes.if_else.if_else_node', 'IFElseNode')
IterationEndNode = LazyNodeClass('workflow.engine.nodes.iteration.iteration_node', 'IterationEndNode')
IterationNode = LazyNodeClass('workflow.engine.nodes.iteration.iteration_node', 'IterationNode')
IterationStartNode = LazyNodeClass('workflow.engine.nodes.iteration.iteration_node', 'IterationStartNode')
KnowledgeNode = LazyNodeClass('workflow.engine.nodes.knowledge.knowledge_node', 'KnowledgeNode')
KnowledgeProNode = LazyNodeClass('workflow.engine.nodes.knowledge_pro.knowledge_pro_node', 'KnowledgeProNode')
SparkLLMNode = LazyNodeClass('workflow.engine.nodes.llm.spark_llm_node', 'SparkLLMNode')
MessageNode = LazyNodeClass('workflow.engine.nodes.message.message_node', 'MessageNode')
ParamsExtractorNode = LazyNodeClass('workflow.engine.nodes.params_extractor.pe_node', 'ParamsExtractorNode')
PGSqlNode = LazyNodeClass('workflow.engine.nodes.pgsql.pgsql_node', 'PGSqlNode')
PluginNode = LazyNodeClass('workflow.engine.nodes.plugin_tool.plugin_node', 'PluginNode')
QuestionAnswerNode = LazyNodeClass('workflow.engine.nodes.question_answer.question_answer_node', 'QuestionAnswerNode')
RPANode = LazyNodeClass('workflow.engine.nodes.rpa.rpa_node', 'RPANode')
StartNode = LazyNodeClass('workflow.engine.nodes.start.start_node', 'StartNode')
TextJoinerNode = LazyNodeClass('workflow.engine.nodes.text_joiner.text_joiner_node', 'TextJoinerNode')

tool_classes = {
    "ifly-code": CodeNode,  # Code execution node for running custom code
    "node-start": StartNode,  # Workflow start node that initiates execution
    "node-end": EndNode,  # Workflow end node that terminates execution
    "plugin": PluginNode,  # Plugin tool node for external integrations
    "knowledge-base": KnowledgeNode,  # Knowledge base node for information retrieval
    "knowledge-pro-base": KnowledgeProNode,  # Professional knowledge base node with advanced features
    "extractor-parameter": ParamsExtractorNode,  # Parameter extraction node for data parsing
    "spark-llm": SparkLLMNode,  # Spark LLM node for language model interactions
    "decision-making": DecisionNode,  # Decision making node for conditional logic
    "if-else": IFElseNode,  # Conditional branching node for flow control
    "message": MessageNode,  # Message output node for displaying results
    "iteration": IterationNode,  # Iteration node for loop operations
    "iteration-node-start": IterationStartNode,  # Iteration start node for loop initialization
    "iteration-node-end": IterationEndNode,  # Iteration end node for loop termination
    "text-joiner": TextJoinerNode,  # Text joining node for content concatenation
    "node-variable": GlobalVariablesNode,  # Global variables node for state management
    "flow": FlowNode,  # Sub-flow node for nested workflow execution
    "agent": AgentNode,  # Agent node for autonomous task execution
    "question-answer": QuestionAnswerNode,  # Question-answer node for Q&A processing
    "database": PGSqlNode,  # PostgreSQL database node for data operations
    "rpa": RPANode,
}
