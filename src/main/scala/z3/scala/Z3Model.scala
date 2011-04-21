package z3.scala

import z3.{Z3Wrapper,Pointer}

object Z3Model {
  implicit def ast2int(model: Z3Model, ast: Z3AST): Option[Int] = {
    val res = model.eval(ast)
    if (res.isEmpty)
      None
    else
      model.context.getNumeralInt(res.get)
  }

  implicit def ast2bool(model: Z3Model, ast: Z3AST): Option[Boolean] = {
    val res = model.eval(ast)
    if (res.isEmpty)
      None
    else
      model.context.getBoolValue(res.get)
  }

  implicit def ast2intSet(model: Z3Model, ast: Z3AST) : Option[Set[Int]] = model.eval(ast) match {
    case None => None
    case Some(evaluated) => model.context.getSetValue(evaluated) match {
      case Some(astSet) =>
        Some(astSet.map(elem => model.evalAs[Int](elem)).foldLeft(Set[Int]())((acc, e) => e match {
          case Some(value) => acc + value
          case None => acc
        }))
      case None => None
    }
  }
}

class Z3Model private[z3](ptr: Long, private val context: Z3Context) extends Pointer(ptr) {
  override def toString : String = context.modelToString(this)

  def delete: Unit = {
    Z3Wrapper.delModel(context.ptr, this.ptr)
  }

  def eval(ast: Z3AST) : Option[Z3AST] = {
    if(this.ptr == 0L) {
      throw new IllegalStateException("The model is not initialized.")
    }
    val out = new Pointer(0L)
    val result = Z3Wrapper.eval(context.ptr, this.ptr, ast.ptr, out)
    if (result) {
      Some(new Z3AST(out.ptr, context))
    } else {
      None
    }
  }

  @deprecated("use `evalAs[Int]` instead")
  def evalAsInt(ast: Z3AST) : Option[Int] = {
    val res = this.eval(ast)
    if(res.isEmpty)
      None
    else
      context.getNumeralInt(res.get)
  }

  @deprecated("use `evalAs[Boolean]` instead")
  def evalAsBool(ast: Z3AST) : Option[Boolean] = {
    val res = this.eval(ast)
    if(res.isEmpty)
      None
    else 
      context.getBoolValue(res.get)
  }

  def evalAs[T](input: Z3AST)(implicit converter: (Z3Model, Z3AST) => Option[T]): Option[T] = {
    converter(this, input)
  }

  def getModelConstants: Iterator[Z3FuncDecl] = {
    val model = this
    new Iterator[Z3FuncDecl] {
      val total: Int = Z3Wrapper.getModelNumConstants(context.ptr, model.ptr)
      var returned: Int = 0

      override def hasNext: Boolean = (returned < total)
      override def next(): Z3FuncDecl = {
        val toReturn = new Z3FuncDecl(Z3Wrapper.getModelConstant(context.ptr, model.ptr, returned), 0, context)
        returned += 1
        toReturn
      }
    }
  }

  def getModelConstantInterpretations : Iterator[(Z3FuncDecl, Z3AST)] = {
    val model = this
    val constantIterator = getModelConstants
    new Iterator[(Z3FuncDecl, Z3AST)] {
      override def hasNext: Boolean = constantIterator.hasNext
      override def next(): (Z3FuncDecl, Z3AST) = {
        val nextConstant = constantIterator.next()
        (nextConstant, model.eval(nextConstant()).get)
      }
    }
  }

  private lazy val constantInterpretationMap: Map[String, Z3AST] =
   getModelConstantInterpretations.map(p => (p._1.getName.toString, p._2)).toMap

  def getModelConstantInterpretation(name: String): Option[Z3AST] =
    constantInterpretationMap.get(name) match {
      case None => None
      case Some(v) => Some(v.asInstanceOf[Z3AST])
    }

  def getModelFuncInterpretations : Iterator[(Z3FuncDecl, Seq[(Seq[Z3AST], Z3AST)], Z3AST)] = {
    val model = this
    new Iterator[(Z3FuncDecl, List[(List[Z3AST], Z3AST)], Z3AST)] {
      val total: Int = Z3Wrapper.getModelNumFuncs(context.ptr, model.ptr)
      var returned: Int = 0

      override def hasNext: Boolean = (returned < total)
      override def next(): (Z3FuncDecl, List[(List[Z3AST], Z3AST)], Z3AST) = {
        val declPtr = Z3Wrapper.getModelFuncDecl(context.ptr, model.ptr, returned)
        val arity = Z3Wrapper.getDomainSize(context.ptr, declPtr)
        val funcDecl = new Z3FuncDecl(declPtr, arity, context)
        val numEntries = Z3Wrapper.getModelFuncNumEntries(context.ptr, model.ptr, returned)
        val entries = for (entryIndex <- 0 until numEntries) yield {
          val numArgs = Z3Wrapper.getModelFuncEntryNumArgs(context.ptr, model.ptr, returned, entryIndex)
          val arguments = for (argIndex <- 0 until numArgs) yield {
            new Z3AST(Z3Wrapper.getModelFuncEntryArg(context.ptr, model.ptr, returned, entryIndex, argIndex), context)
          }
          (arguments.toList, new Z3AST(Z3Wrapper.getModelFuncEntryValue(context.ptr, model.ptr, returned, entryIndex), context))
        }
        val elseValue = new Z3AST(Z3Wrapper.getModelFuncElse(context.ptr, model.ptr, returned), context)
        returned += 1
        (funcDecl, entries.toList, elseValue)
      }
    }
  }

  private lazy val funcInterpretationMap: Map[String, (Seq[(Seq[Z3AST], Z3AST)], Z3AST)] =
    getModelFuncInterpretations.map(i => (i._1.getName.toString, (i._2, i._3))).toMap

  def getModelFuncInterpretation(name: String): Option[Z3Function] = {
    // funcInterpretationMap.get(name) match {
    //     case Some(interpretation) => Some(new Z3Function(interpretation))
    //     case None => None
    // }

    getModelFuncInterpretations.find(i => i._1.getName.toString == name) match {
      case Some(i) => Some(new Z3Function((i._2, i._3)))
      case None => None
    }
  }

}
