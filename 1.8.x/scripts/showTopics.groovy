// @ExecutionModes({ON_SELECTED_NODE})


this.HIDDEN_NODES = ['Question'] 


def showTopics(node) {
  if (node.children.find { HIDDEN_NODES.contains(it.style.name) }) {
	node.folded = true
	return
  }

  node.folded = false
  node.children.each { showTopics(it) }
}

def foldSiblingsRec(def node) {
  if (node.parent == null) 
    return;
  
  node.parent.children.findAll { it != node }.each {
    it.folded = true
  }
  
  foldSiblingsRec(node.parent)
}

foldSiblingsRec(node)
showTopics(node)
