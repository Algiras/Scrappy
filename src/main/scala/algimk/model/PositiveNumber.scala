package algimk.model

sealed trait PositiveNumber {
  def value: Int
}

object PositiveNumber {
  def apply(nr: Int): Option[PositiveNumber] = if(nr > 0) Some(new PositiveNumber{
    override def value: Int = nr
  }) else None
}
