// @ExecutionModes({ON_SELECTED_NODE})


final HIDDEN_NODES = ['Question'] 



def foldSiblingsRec(def node) {
  if (node.parent == null) 
    return;
  
  node.parent.children.findAll { it != node }.each {
    it.folded = true
  }
  
  foldSiblingsRec(node.parent)
}

foldSiblingsRec(node)
node.findAll().each {
  it.folded = false
}
